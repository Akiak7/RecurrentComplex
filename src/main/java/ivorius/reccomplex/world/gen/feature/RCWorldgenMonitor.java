/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.world.gen.feature;

import ivorius.reccomplex.RecurrentComplex;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RCWorldgenMonitor
{
    protected final static Deque<String> actions = new ArrayDeque<>();
    private static final ExecutorService REPORT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "RCWorldgenMonitor-Reporter");
        thread.setDaemon(true);
        return thread;
    });

    public static void start(String action)
    {
        actions.push(action);
    }

    public static void stop()
    {
        actions.pop();
    }

    public static void report(Runnable runnable)
    {
        REPORT_EXECUTOR.execute(runnable);
    }

    public static void create()
    {
        WorldgenMonitor.create("Recurrent Complex", (p, d) -> {
            if (!actions.isEmpty())
                RecurrentComplex.logger.warn("Cascading chunk generation happening while {}", actions.peek());
        });
    }
}
