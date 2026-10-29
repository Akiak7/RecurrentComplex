package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import ivorius.ivtoolkit.maze.components.PlacedMazeComponent;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;

import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

/** Server-session service. It holds detached layout requests only; no worlds or chunks. */
public final class FreshMazeLayouts
{
    private static FreshLayoutQueue<MazeLayoutRequest, List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>>> queue;
    private static long nextReportNanos;
    private static long[] reportedStatistics;
    private static int capacity;
    private static boolean warnedEviction;

    private FreshMazeLayouts() {}

    private static void ensureQueue()
    {
        if (queue == null)
        {
            capacity = Math.max(0, RCConfig.mazeLayoutQueueMaxConfigurations);
            queue = new FreshLayoutQueue<>(capacity, Math.max(1, RCConfig.mazeLayoutQueueSize));
            nextReportNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
            reportedStatistics = null;
            warnedEviction = false;
        }
    }

    public static synchronized List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> poll(MazeLayoutRequest request, long seed)
    {
        ensureQueue();
        List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> result = queue.poll(request, solver(request), seed);
        if (!warnedEviction && queue.evictionCount() > 0 && RecurrentComplex.logger != null)
        {
            warnedEviction = true;
            RecurrentComplex.logger.warn("Maze queue reached its configuration capacity ({}); least recently used configurations are being evicted. "
                    + "Consider increasing mazeLayoutQueueMaxConfigurations, or set it to 0 to disable capacity eviction.", capacity);
        }
        return result;
    }

    public static synchronized void replenish(MazeLayoutRequest request)
    {
        if (queue != null) queue.replenish(request);
    }

    public static synchronized long[] statistics()
    {
        return queue == null ? new long[7] : queue.statistics();
    }

    public static synchronized void clear()
    {
        clear("reset");
    }

    public static synchronized void clear(String reason)
    {
        if (queue != null)
        {
            reportSummary(reason);
            queue.close();
        }
        queue = null;
    }

    /** At most once per minute and only when meaningful counters changed. */
    public static void reportIfDue()
    {
        reportIfDue(System.nanoTime());
    }

    static synchronized void reportIfDue(long nowNanos)
    {
        if (queue == null || nowNanos - nextReportNanos < 0) return;
        nextReportNanos = nowNanos + TimeUnit.MINUTES.toNanos(1);
        reportSummary("periodic");
    }

    private static void reportSummary(String reason)
    {
        long[] stats = queue.statistics();
        long requests = stats[0] + stats[1];
        if (requests == 0 || RecurrentComplex.logger == null
                || Arrays.equals(stats, reportedStatistics)) return;
        RecurrentComplex.logger.info(String.format(Locale.ROOT,
                "Maze queue summary (%s): eligible requests=%d, ready hits=%d (%.1f%%), slow fallbacks=%d, discarded background attempts=%d, "
                        + "configuration capacity=%s, cached configurations=%d, ready layouts=%d, evictions=%d. Counts since last queue reset.",
                reason, requests, stats[0], 100d * stats[0] / requests, stats[1], stats[2],
                capacity == 0 ? "unlimited" : Integer.toString(capacity), stats[4], stats[5], stats[6]));
        reportedStatistics = stats;
    }

    public static synchronized boolean prime(MazeLayoutRequest request, long seed)
    {
        ensureQueue();
        LongFunction<List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>>> solve = solver(request);
        return queue.prime(request, solve, solve, seed, 3);
    }

    private static LongFunction<List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>>> solver(MazeLayoutRequest request)
    {
        long timeout = request.backgroundTimeoutMillis(RCConfig.mazeLayoutBackgroundTimeoutMultiplier);
        return seed -> request.solve(seed, timeout);
    }

    public static synchronized boolean firstLayoutsReady()
    {
        return queue == null || queue.firstLayoutsReady();
    }

    public static synchronized void finishPriming()
    {
        if (queue != null) queue.finishPriming();
    }

    public static void awaitProgress(long millis) throws InterruptedException
    {
        FreshLayoutQueue<?, ?> current;
        synchronized (FreshMazeLayouts.class) { current = queue; }
        if (current != null) current.awaitProgress(millis);
    }
}
