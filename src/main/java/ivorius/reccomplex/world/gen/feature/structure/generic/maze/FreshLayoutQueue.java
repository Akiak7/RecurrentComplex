package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.LongFunction;

/** Consume-once results with optional capacity eviction. Suppliers own detached data, never a live world.
 * Polling never waits for a solve. Call replenish after a hit or foreground fallback. */
public final class FreshLayoutQueue<K, V> implements AutoCloseable
{
    private final int maxKeys, depth;
    private final LinkedHashMap<K, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Deque<Entry> pending = new ArrayDeque<>();
    private final Thread worker;
    private boolean closed;
    private long hits, misses, failures, produced, evictions;

    public FreshLayoutQueue(int maxKeys, int depth)
    {
        if (maxKeys < 0 || depth < 1) throw new IllegalArgumentException();
        this.maxKeys = maxKeys;
        this.depth = depth;
        worker = new Thread(this::work, "RecurrentComplex-maze-layouts");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    @Nullable
    public synchronized V poll(K key, LongFunction<V> solve, long firstSeed)
    {
        if (closed) return null;
        Entry entry = entries.get(key);
        if (entry == null)
        {
            if (maxKeys > 0 && entries.size() == maxKeys)
            {
                Iterator<Entry> iterator = entries.values().iterator();
                Entry evicted = iterator.next();
                iterator.remove();
                evicted.active = false;
                pending.remove(evicted);
                evictions++;
            }
            entry = new Entry(solve, firstSeed);
            entries.put(key, entry);
        }
        V result = entry.ready.pollFirst();
        if (result == null) misses++; else hits++;
        return result;
    }

    public synchronized void replenish(K key)
    {
        Entry entry = entries.get(key);
        if (!closed && entry != null)
        {
            entry.failed = false;
            entry.priming = false; // Actual demand takes precedence over speculative first coverage.
            schedule(entry);
            if (entry.queued)
            {
                pending.remove(entry);
                pending.addFirst(entry);
            }
        }
    }

    /** Warm one result without recording a demand miss, consuming a result or evicting a key. */
    public synchronized boolean prime(K key, LongFunction<V> solve, long firstSeed)
    {
        return prime(key, solve, solve, firstSeed, 1);
    }

    public synchronized boolean prime(K key, LongFunction<V> solve, LongFunction<V> warmSolve, long firstSeed, int maxAttempts)
    {
        if (closed) return false;
        Entry entry = entries.get(key);
        if (entry == null)
        {
            if (maxKeys > 0 && entries.size() == maxKeys) return false;
            entry = new Entry(solve, firstSeed);
            entries.put(key, entry);
        }
        entry.priming = true;
        entry.fillAfterPriming = false;
        entry.warmSolve = warmSolve;
        entry.maxWarmAttempts = Math.max(1, maxAttempts);
        if (!entry.failed) schedule(entry);
        return true;
    }

    /** First requests have a result or exhausted their bounded speculative attempts. */
    public synchronized boolean firstLayoutsReady()
    {
        return entries.values().stream().allMatch(e -> !e.ready.isEmpty() || e.failed);
    }

    public synchronized void finishPriming()
    {
        for (Entry entry : entries.values())
        {
            // Discovery has ended, but an unfinished first layout keeps its bounded retries.
            entry.fillAfterPriming = true;
            if (!entry.ready.isEmpty() || entry.failed) entry.priming = false;
            if (!entry.failed) schedule(entry);
        }
    }

    /** Startup-only bounded wait. Runtime demand continues to use nonblocking poll. */
    public synchronized void awaitProgress(long millis) throws InterruptedException
    {
        if (!closed && millis > 0) wait(millis);
    }

    private void schedule(Entry entry)
    {
        if (entry.active && !entry.queued && !entry.building && entry.ready.size() < (entry.priming ? 1 : depth))
        {
            entry.queued = true;
            pending.addLast(entry);
            notifyAll();
        }
    }

    private void work()
    {
        while (true)
        {
            Entry entry;
            long seed;
            LongFunction<V> solve;
            boolean speculative;
            synchronized (this)
            {
                while (!closed && pending.isEmpty())
                    try { wait(); }
                    catch (InterruptedException e) { if (closed) return; }
                if (closed) return;
                entry = pending.removeFirst();
                entry.queued = false;
                entry.building = true;
                seed = entry.nextSeed++;
                speculative = entry.priming;
                solve = speculative ? entry.warmSolve : entry.solve;
            }
            V result = null;
            boolean failed = false;
            try { result = Objects.requireNonNull(solve.apply(seed)); }
            catch (RuntimeException e) { failed = true; }
            synchronized (this)
            {
                entry.building = false;
                if (closed) return;
                if (entry.active)
                {
                    if (speculative) entry.warmAttempts++;
                    boolean retry = failed && speculative && entry.priming && entry.warmAttempts < entry.maxWarmAttempts;
                    entry.failed = failed && !retry;
                    if (failed)
                    {
                        failures++;
                        if (retry) schedule(entry); // Yield to other keys before trying a fresh seed.
                    }
                    else
                    {
                        entry.ready.addLast(result);
                        produced++;
                        if (entry.fillAfterPriming) entry.priming = false;
                        schedule(entry); // Round robin: one solve before the next pending key.
                    }
                    // Ordinary failures retry on demand; speculative retries have a fixed bound.
                }
                notifyAll();
            }
        }
    }

    public synchronized long[] statistics()
    {
        return new long[]{hits, misses, failures, produced, entries.size(),
                entries.values().stream().mapToInt(e -> e.ready.size()).sum(), evictions};
    }

    public synchronized long evictionCount() { return evictions; }

    public synchronized int readyCount(K key)
    {
        Entry entry = entries.get(key);
        return entry == null ? 0 : entry.ready.size();
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        entries.values().forEach(e -> e.active = false);
        entries.clear();
        pending.clear();
        worker.interrupt();
        notifyAll();
    }

    private final class Entry
    {
        final LongFunction<V> solve;
        LongFunction<V> warmSolve;
        final Deque<V> ready = new ArrayDeque<>();
        long nextSeed;
        boolean active = true, queued, building, priming, fillAfterPriming, failed;
        int warmAttempts, maxWarmAttempts = 1;

        Entry(LongFunction<V> solve, long firstSeed)
        {
            this.solve = solve;
            warmSolve = solve;
            nextSeed = firstSeed;
        }
    }
}
