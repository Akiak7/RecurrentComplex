package ivorius.reccomplex.world.gen.feature;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import static org.junit.Assert.*;

public class RCGenerationDiagnosticFileTest
{
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void configIsExplicitOptInAndNeedsNoCoordinatesOrJvmProperties() throws Exception
    {
        File config = new File(temp.getRoot(), "reccomplex-diagnostics.properties");
        assertFalse(RCGenerationDiagnostics.Settings.readFile(config).enabled);
        Files.write(config.toPath(), "enabled=true\n".getBytes(StandardCharsets.UTF_8));
        RCGenerationDiagnostics.Settings enabled = RCGenerationDiagnostics.Settings.readFile(config);
        assertTrue(enabled.enabled);
        assertTrue(enabled.contains(-90000, 50000));
        assertTrue(enabled.dimensionMatches(-1));
        Files.write(config.toPath(), "enabled=false\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(RCGenerationDiagnostics.Settings.readFile(config).enabled);
        Files.write(config.toPath(), "enabled=true\nreccomplex.trace.area=invalid\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(RCGenerationDiagnostics.Settings.readFile(config).enabled);
        assertNotNull(RCGenerationDiagnostics.Settings.readFile(config).error);
        Files.write(config.toPath(), "enabled=true\nreccomplex.trace.dimension=oops\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(RCGenerationDiagnostics.Settings.readFile(config).enabled);
    }

    @Test public void laterNoiseKeepsInitialContextAndMetadataWhileLossIsExplicit() throws Exception
    {
        try (RCGenerationDiagnosticFile f = small(3))
        {
            UUID uuid = new UUID(1, 2);
            RCGenerationDiagnosticFile.Evidence e = f.begin(null, true);
            f.record(e, true, "BEGIN", "structure=tower chosenY=230");
            f.record(e, true, "PLANNED", "full=10,230,20,60,250,70 clip=8,0,8,23,255,23");
            f.bind(e, uuid);
            f.record(e, true, "ENTRY_ADD", "entry=" + uuid);
            f.metadata("effectiveClean=false build=fixture");
            for (int i = 0; i < 100; i++)
            {
                f.record(e, true, "SAMPLE", "initial-detail-" + i);
                f.record(e, false, "SUPPORT", "later-detail-" + i);
            }
            assertSame(e, f.begin(uuid, false));
            f.record(e, false, "ENTRY_REMOVE", "caller=cleanup");
            f.save();
            Map<String, String> zip = read(f.path);
            String context = zip.get("structures/context-1.txt");
            assertTrue(context.contains("chosenY=230"));
            assertTrue(context.contains("full=10,230,20,60,250,70"));
            assertTrue(context.contains("entry=" + uuid));
            assertTrue(context.contains("caller=cleanup"));
            assertTrue(context.contains("INITIAL_DETAIL TRUNCATED_records="));
            assertFalse(context.contains("INITIAL_DETAIL TRUNCATED_records=0"));
            assertFalse(context.contains("LATER_DETAIL EVICTED_records=0"));
            assertTrue(zip.get("session.txt").contains("effectiveClean=false"));
        }
    }

    @Test public void wholeStructureEvictionDoesNotPretendToRecoverInitialEvidence() throws Exception
    {
        try (RCGenerationDiagnosticFile f = small(1))
        {
            UUID first = new UUID(1, 1), second = new UUID(2, 2);
            RCGenerationDiagnosticFile.Evidence old = f.begin(first, true);
            f.record(old, true, "BEGIN", "structure=old xyz=500,64,700");
            f.begin(second, true);
            assertFalse(f.retained(old));
            f.record(old, false, "SUPPORT", "ignored-after-eviction");
            RCGenerationDiagnosticFile.Evidence resumed = f.begin(first, false);
            assertFalse(resumed.hasInitial);
            f.record(resumed, false, "PASS", "completion");
            f.save();
            Map<String, String> zip = read(f.path);
            assertTrue(zip.get("evictions.txt").contains("STRUCTURE_EVICTED context=1"));
            assertTrue(zip.get("session.txt").contains("recordsForEvictedContexts=1"));
            assertTrue(zip.get("structures/context-3.txt").contains("INITIAL_CONTEXT_UNAVAILABLE"));
            assertEquals(1, zip.keySet().stream().filter(k -> k.startsWith("structures/")).count());
        }
    }

    @Test public void failedAndUnsavedAttemptsCannotEvictSavedStructures() throws Exception
    {
        try (RCGenerationDiagnosticFile f = small(2))
        {
            UUID uuid = new UUID(1, 9);
            RCGenerationDiagnosticFile.Evidence saved = f.begin(null, true);
            f.record(saved, true, "BEGIN", "structure=megacastle");
            f.bind(saved, uuid);
            for (int i = 0; i < 100; i++)
                f.record(f.begin(null, true), true, "RESULT", "failure=no-place");
            assertSame(saved, f.begin(uuid, false));
            f.save();
            assertTrue(read(f.path).get("structures/context-1.txt").contains("megacastle"));
        }
    }

    @Test public void finalSaveIsSelfContainedAndLaterSessionsCannotOverwriteIt() throws Exception
    {
        RCGenerationDiagnosticFile first = small(2);
        first.metadata("versions=fixture effectiveSettings=fixture build=fixture");
        first.player("player-1", "dim=0 xyz=1,65,2 yaw=90 pitch=0");
        first.record(first.begin(null, true), true, "PLANNED", "chosenY=64");
        first.close();
        byte[] original = Files.readAllBytes(first.path);
        try (RCGenerationDiagnosticFile second = small(2))
        {
            assertNotEquals(first.path, second.path);
            second.record(null, false, "SESSION", "another session");
            second.save();
        }
        assertArrayEquals(original, Files.readAllBytes(first.path));
        Map<String, String> zip = read(first.path);
        assertTrue(zip.get("session.txt").contains("sessionClosed=true"));
        assertTrue(zip.get("session.txt").contains("versions=fixture"));
        assertTrue(zip.get("session.txt").contains("xyz=1,65,2"));
        assertTrue(zip.get("structures/context-1.txt").contains("chosenY=64"));
        try (java.util.stream.Stream<Path> paths = Files.list(temp.getRoot().toPath()))
        { assertEquals(2, paths.count()); }
    }

    @Test public void snapshotsAreReadableDuringConcurrentRecordingAndClose() throws Exception
    {
        RCGenerationDiagnosticFile f = small(2);
        RCGenerationDiagnosticFile.Evidence e = f.begin(new UUID(2, 5), true);
        f.record(e, true, "BEGIN", "structure=tower");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> records = workers.submit(() -> {
                for (int i = 0; i < 1000; i++) f.record(e, false, "PASS", "completion=" + i);
            });
            Future<?> saves = workers.submit(() -> { for (int i = 0; i < 8; i++) f.save(); });
            records.get(10, TimeUnit.SECONDS);
            saves.get(10, TimeUnit.SECONDS);
            f.close();
            assertTrue(read(f.path).get("structures/context-1.txt").contains("completion=999"));
            assertEquals("none", f.lastOutputError());
        }
        finally { workers.shutdownNow(); f.close(); }
    }

    @Test public void backgroundWriterSavesAutomaticallyWithoutManualSave() throws Exception
    {
        try (RCGenerationDiagnosticFile f = small(2))
        {
            f.record(null, false, "AUTOSAVE_TEST", "written-by-scheduler");
            f.startSaving();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean found = false;
            while (!found && System.nanoTime() < deadline)
            {
                if (Files.size(f.path) > 0)
                    found = read(f.path).get("recent-events.txt").contains("written-by-scheduler");
                if (!found) Thread.sleep(10);
            }
            assertTrue("scheduled save did not produce its self-contained file", found);
        }
    }

    @Test public void outputFailureKeepsLastValidSnapshotAndCanRecover() throws Exception
    {
        try (RCGenerationDiagnosticFile f = small(2))
        {
            f.save();
            Path backup = temp.getRoot().toPath().resolve("saved.zip");
            Files.move(f.path, backup);
            Files.createDirectory(f.path);
            Files.write(f.path.resolve("block-move"), new byte[]{1});
            f.save();
            assertNotEquals("none", f.lastOutputError());
            assertTrue(read(backup).containsKey("session.txt"));
            Files.delete(f.path.resolve("block-move"));
            Files.delete(f.path);
            Files.move(backup, f.path);
            f.save();
            assertTrue(read(f.path).get("session.txt").contains("last successful snapshot may be stale"));
        }
    }

    @Test public void metadataAndHistoryRemainBoundedAndPathsAreRedacted() throws Exception
    {
        try (RCGenerationDiagnosticFile f = small(2))
        {
            String payload = String.join("", Collections.nCopies(2500, "x"));
            for (int i = 0; i < 500; i++)
            {
                f.metadata("setting=" + payload);
                f.record(null, false, "EVENT", payload);
            }
            f.record(null, false, "ERROR", "source=/Users/private-account/mods/thing.jar source=C:\\Users\\private-account\\thing.jar");
            f.save();
            Map<String, String> zip = read(f.path);
            assertTrue(zip.get("session.txt").length() < 1060000);
            assertFalse(zip.get("session.txt").contains("METADATA TRUNCATED_records=0"));
            assertTrue(zip.get("recent-events.txt").length() < 263000);
            assertFalse(zip.get("recent-events.txt").contains("SESSION_HISTORY EVICTED_records=0"));
            assertFalse(zip.toString().contains("private-account"));
        }
    }

    @Test public void sustainedActivityStaysWithinDefaultSavedAndUnsavedBudgets() throws Exception
    {
        try (RCGenerationDiagnosticFile f = new RCGenerationDiagnosticFile(temp.getRoot().toPath()))
        {
            for (int i = 0; i < 1000; i++)
            {
                RCGenerationDiagnosticFile.Evidence e = f.begin(null, true);
                f.record(e, true, "BEGIN", "structure=castle location=" + i);
                f.bind(e, new UUID(0, i));
                f.record(e, true, "PLANNED", "chosenY=80 full=fixture");
                for (int j = 0; j < 20; j++) f.record(e, false, "SUPPORT", "population=" + j);
            }
            for (int i = 0; i < 1000; i++) f.record(f.begin(null, true), true, "RESULT", "failure=fixture");
            f.save();
            Map<String, String> zip = read(f.path);
            assertEquals(RCGenerationDiagnosticFile.MAX_STRUCTURES + RCGenerationDiagnosticFile.MAX_OTHER_ATTEMPTS,
                    zip.keySet().stream().filter(k -> k.startsWith("structures/")).count());
            assertTrue(zip.get("session.txt").contains("evictedStructures=1840"));
            assertTrue(zip.values().stream().mapToInt(String::length).sum() < 2 * 1024 * 1024);
            assertTrue(zip.values().stream().anyMatch(s -> s.contains("location=999")));
        }
    }

    private RCGenerationDiagnosticFile small(int capacity) throws IOException
    { return new RCGenerationDiagnosticFile(temp.getRoot().toPath(), capacity, 400, 400); }

    static Map<String, String> read(Path file) throws IOException
    {
        Map<String, String> contents = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file)))
        {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;)
            {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n; (n = zip.read(buffer)) != -1;) bytes.write(buffer, 0, n);
                contents.put(entry.getName(), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return contents;
    }
}
