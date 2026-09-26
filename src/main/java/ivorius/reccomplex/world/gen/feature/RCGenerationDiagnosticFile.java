package ivorius.reccomplex.world.gen.feature;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The one-session RC diagnostic artifact. Initial evidence is retained with its structure, never in a global tail. */
final class RCGenerationDiagnosticFile implements AutoCloseable
{
    static final int MAX_STRUCTURES = 128, INITIAL_CHARS = 65536, LATER_CHARS = 32768;
    static final int MAX_OTHER_ATTEMPTS = 32;
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)[a-z]:[\\\\/][^\\s,;]+");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![a-zA-Z0-9_:])/[^\\s,;]+");
    private final int capacity, initialLimit, laterLimit;
    private final LinkedHashMap<Long, Evidence> structures = new LinkedHashMap<>(16, .75f, true);
    private final Map<UUID, Evidence> entries = new HashMap<>();
    private final Lines metadata = new Lines(1024 * 1024, false);
    private final Lines history = new Lines(256 * 1024, true);
    private final Lines evictions = new Lines(128 * 1024, true);
    private final LinkedHashMap<String, String> players = new LinkedHashMap<>();
    private final Map<String, Long> counts = new TreeMap<>();
    final Path path;
    private ScheduledExecutorService writer;
    private long nextID, sequence, evicted, lateRecords;
    private String outputError = "none";
    private boolean closed;

    RCGenerationDiagnosticFile(Path directory) throws IOException
    {
        this(directory, MAX_STRUCTURES, INITIAL_CHARS, LATER_CHARS);
    }

    RCGenerationDiagnosticFile(Path directory, int capacity, int initialLimit, int laterLimit) throws IOException
    {
        this.capacity = capacity;
        this.initialLimit = initialLimit;
        this.laterLimit = laterLimit;
        Files.createDirectories(directory);
        // CREATE_NEW guarantees that a later launch cannot replace an earlier session, even within the same millisecond.
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS", Locale.ROOT).format(new Date());
        Path candidate;
        for (int suffix = 0;; suffix++)
        {
            candidate = directory.resolve("RC-diagnostic-" + stamp + "-" + suffix + ".zip");
            try { Files.createFile(candidate); break; }
            catch (FileAlreadyExistsException ignored) { }
        }
        path = candidate;
        metadata("FORMAT=2 build=dregora-diag2 startedUTC=" + java.time.Instant.now());
        metadata("RETENTION maxSavedStructures=" + capacity + " maxUnsavedAttempts=" + Math.min(capacity, MAX_OTHER_ATTEMPTS)
                + " initialChars=" + initialLimit + " laterChars=" + laterLimit
                + " metadataChars=1048576 recentEventChars=262144 evictionChars=131072; limits count UTF-16 characters");
        metadata("COVERAGE Initial summaries and initial detail stay with each tracked structure. Later detail is a bounded tail."
                + " Entire structures are evicted by least recent activity. Old structures discovered later may have no evidence."
                + " No absence of a record proves absence of a game event. Unknown terrain is never air.");
        metadata("PRIVACY No player names/UUIDs, accounts, IPs, save names, or mod source paths are collected."
                + " Structure UUIDs, world seeds, dimensions and anonymous player positions are diagnostic data.");
    }

    void startSaving()
    {
        writer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RC-Diagnostic-Writer");
            t.setDaemon(true);
            return t;
        });
        writer.scheduleWithFixedDelay(this::save, 0, 30, TimeUnit.SECONDS);
    }

    synchronized Evidence begin(UUID entry, boolean initial)
    {
        if (closed) return null;
        Evidence found = entry == null ? null : entries.get(entry);
        if (found != null) { found.hasInitial |= initial; structures.get(found.id); return found; }
        makeRoom(entry != null);
        Evidence evidence = new Evidence(++nextID, entry, initial, initialLimit, laterLimit);
        structures.put(evidence.id, evidence);
        if (entry != null) entries.put(entry, evidence);
        return evidence;
    }

    private void makeRoom(boolean saved)
    {
        int limit = saved ? capacity : Math.min(capacity, MAX_OTHER_ATTEMPTS);
        if (structures.values().stream().filter(e -> (e.entry != null) == saved).count() >= limit)
        {
            Evidence old = structures.values().stream().filter(e -> (e.entry != null) == saved).findFirst().get();
            structures.remove(old.id);
            if (old.entry != null) entries.remove(old.entry);
            evicted++;
            evictions.add("STRUCTURE_EVICTED context=" + old.id + " entry=" + old.entry + " " + old.identity
                    + " all-initial-and-later-evidence=lost");
        }
    }

    synchronized void bind(Evidence evidence, UUID entry)
    {
        if (!retained(evidence)) return;
        if (evidence.entry != null && !evidence.entry.equals(entry)) return;
        if (evidence.entry == null) makeRoom(true);
        evidence.entry = entry;
        entries.put(entry, evidence);
    }

    synchronized Evidence find(UUID entry) { return entries.get(entry); }
    synchronized boolean retained(Evidence evidence) { return evidence != null && structures.get(evidence.id) == evidence; }

    synchronized void record(Evidence evidence, boolean initial, String event, String detail)
    {
        if (closed) return;
        String line = line(event, detail);
        if (evidence == null) { history.add(line); return; }
        if (!retained(evidence)) { lateRecords++; return; }
        structures.get(evidence.id); // Observed activity refreshes retention; snapshots do not.
        if (event.equals("BEGIN") && evidence.identity.isEmpty()) evidence.identity = safe(detail);
        if (initial && Arrays.asList("BEGIN", "PLACEMENT", "PLANNED", "RESULT", "ENTRY_ADD").contains(event))
            evidence.summary.put(event, line); // Reserved slots survive truncation of verbose initial observations.
        if (event.equals("ENTRY_LOADED")) evidence.summary.put("SAVED_ENTRY", line);
        (initial ? evidence.initial : evidence.later).add(line);
    }

    synchronized void metadata(String line) { if (!closed) metadata.add(safe(line)); }
    synchronized void count(String name) { if (counts.size() < 16 || counts.containsKey(name)) counts.merge(name, 1L, Long::sum); }

    synchronized void player(String anonymousID, String location)
    {
        if (players.size() < 32 || players.containsKey(anonymousID)) players.put(anonymousID, java.time.Instant.now() + " " + safe(location));
        else history.add("PLAYER_COVERAGE_LIMIT maximum=32");
        history.add(line("PLAYER", anonymousID + " " + location));
    }

    private String line(String event, String detail)
    {
        return "seq=" + (++sequence) + " time=" + java.time.Instant.now() + " " + safe(event) + " " + safe(detail);
    }

    static String safe(Object object)
    {
        String text = String.valueOf(object).replace('\n', ' ').replace('\r', ' ');
        // Defense in depth for exception/config strings, not a reason to collect arbitrary files or system properties.
        text = UNIX_PATH.matcher(WINDOWS_PATH.matcher(text).replaceAll("<path>")).replaceAll("<path>");
        return text.length() > 3500 ? text.substring(0, 3500) + " [TEXT_TRUNCATED]" : text;
    }

    private synchronized Map<String, String> snapshot()
    {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("session.txt", metadata.render("METADATA") + "\nSNAPSHOT time=" + java.time.Instant.now()
                + " sessionClosed=" + closed + " evictedStructures=" + evicted + " recordsForEvictedContexts=" + lateRecords
                + " outputError=" + outputError + "\nCounters (empty population/check events are counted, not individually logged): " + counts
                + "\nLast anonymous player observations:\n" + players + "\n"
                + "Abrupt termination may lose up to the last autosave interval (30 seconds), or more if output failed.\n");
        result.put("recent-events.txt", history.render("SESSION_HISTORY"));
        result.put("evictions.txt", evictions.render("EVICTION_HISTORY"));
        for (Evidence e : structures.values())
            result.put("structures/context-" + e.id + ".txt", "context=" + e.id + " entry=" + e.entry
                    + "\n" + (e.hasInitial ? "INITIAL_CONTEXT captured-in-this-session (check truncation counters)"
                    : "INITIAL_CONTEXT_UNAVAILABLE initial generation predates capture, was evicted, or was excluded")
                    + "\nRESERVED_INITIAL_SUMMARY\n" + String.join("\n", e.summary.values())
                    + "\n" + e.initial.render("INITIAL_DETAIL") + "\n" + e.later.render("LATER_DETAIL"));
        return result;
    }

    // Disk/compression work is serialized here, outside the collector's lock and outside game callbacks.
    synchronized String lastOutputError() { return outputError; }
    private final Object outputLock = new Object();

    void save()
    {
        synchronized (outputLock)
        {
            Path temp = null;
            try
            {
                Map<String, String> snapshot = snapshot();
                temp = Files.createTempFile(path.getParent(), ".rcdiag-", ".tmp");
                try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp))))
                {
                    zip.setLevel(1); // Favor CPU time over the smallest possible file during pregeneration.
                    for (Map.Entry<String, String> entry : snapshot.entrySet())
                    {
                        zip.putNextEntry(new ZipEntry(entry.getKey()));
                        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                }
                try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
                // Retain a previous failure in subsequent files, even after a successful retry.
            }
            catch (IOException | RuntimeException e)
            {
                synchronized (this) { outputError = e.getClass().getSimpleName() + "; last successful snapshot may be stale"; }
            }
            finally
            {
                if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    @Override public void close()
    {
        if (writer != null) writer.shutdown();
        synchronized (this) { closed = true; }
        save();
    }

    static final class Evidence
    {
        final long id;
        UUID entry;
        boolean hasInitial;
        String identity = "";
        final Map<String, String> summary = new LinkedHashMap<>();
        final Lines initial, later;
        Evidence(long id, UUID entry, boolean hasInitial, int initialLimit, int laterLimit)
        {
            this.id = id; this.entry = entry; this.hasInitial = hasInitial;
            initial = new Lines(initialLimit, false);
            later = new Lines(laterLimit, true);
        }
    }

    private static final class Lines
    {
        final int limit;
        final boolean tail;
        final Deque<String> lines = new ArrayDeque<>();
        int chars;
        long lost;
        Lines(int limit, boolean tail) { this.limit = limit; this.tail = tail; }
        void add(String line)
        {
            int size = line.length() + 1;
            if (size > limit || !tail && chars + size > limit) { lost++; return; }
            while (chars + size > limit) { chars -= lines.removeFirst().length() + 1; lost++; }
            lines.addLast(line); chars += size;
        }
        String render(String label)
        {
            return label + " " + (tail ? "EVICTED" : "TRUNCATED") + "_records=" + lost
                    + " retainedChars=" + chars + "\n" + String.join("\n", lines) + "\n";
        }
    }
}
