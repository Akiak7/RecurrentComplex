package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class FreshLayoutQueueTest
{
    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean())
        {
            if (System.nanoTime() >= deadline) fail("Queue did not reach expected state");
            Thread.sleep(1);
        }
    }

    @Test public void consumesEachResultOnceAndRefillsWithNewSeeds() throws Exception
    {
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(2, 2))
        {
            AtomicInteger calls = new AtomicInteger();
            assertNull(queue.poll("a", seed -> { calls.incrementAndGet(); return seed; }, 100));
            assertEquals(0, calls.get()); // Initial fill is deferred until foreground fallback finishes.
            queue.replenish("a");
            await(() -> queue.readyCount("a") == 2);
            assertEquals(Long.valueOf(100), queue.poll("a", seed -> seed, 900));
            assertEquals(Long.valueOf(101), queue.poll("a", seed -> seed, 900));
            assertNull(queue.poll("a", seed -> seed, 900));
            queue.replenish("a");
            await(() -> queue.readyCount("a") == 2);
            assertEquals(Long.valueOf(102), queue.poll("a", seed -> seed, 900));
            assertEquals(4, calls.get());
        }
    }

    @Test public void blockedWorkerDoesNotBlockPollingOrRetainEvictedResults() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 1))
        {
            queue.poll("old", seed -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { throw new CancellationException(); }
                return seed;
            }, 1);
            queue.replenish("old");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // This completes while the worker is deliberately held by the latch.
            assertNull(queue.poll("new", seed -> seed, 20));
            queue.replenish("new");
            assertEquals(1, queue.statistics()[4]);
            assertEquals(1, queue.statistics()[6]);
            release.countDown();
            await(() -> queue.readyCount("new") == 1);
            assertEquals(0, queue.readyCount("old"));
            assertEquals(Long.valueOf(20), queue.poll("new", seed -> seed, 0));
            assertEquals(1, queue.statistics()[3]);
        }
        finally { release.countDown(); }
    }

    @Test public void failuresRetryOnlyAfterAnotherRequest() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 2))
        {
            queue.poll("a", seed -> {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("bad layout");
                return seed;
            }, 10);
            queue.replenish("a");
            await(() -> queue.statistics()[2] == 1);
            assertEquals(1, calls.get());
            assertNull(queue.poll("a", seed -> seed, 900));
            queue.replenish("a");
            await(() -> queue.readyCount("a") == 2);
            assertEquals(Long.valueOf(11), queue.poll("a", seed -> seed, 900));
        }
    }

    @Test public void unlimitedCapacityRetainsMoreThanSixteenConfigurations() throws Exception
    {
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(0, 1))
        {
            for (int i = 0; i < 40; i++) assertTrue(queue.prime("maze" + i, seed -> seed, i));
            await(queue::firstLayoutsReady);
            assertEquals(40, queue.statistics()[4]);
            assertEquals(40, queue.statistics()[5]);
            for (int i = 0; i < 40; i++) assertEquals(Long.valueOf(i), queue.poll("maze" + i, seed -> seed, 999));
            // Runtime discoveries also remain cached when the queue grows further.
            assertNull(queue.poll("runtime", seed -> seed, 100));
            queue.replenish("runtime");
            await(() -> queue.readyCount("runtime") == 1);
            assertEquals(41, queue.statistics()[4]);
            assertEquals(0, queue.evictionCount());
        }
    }

    @Test public void oneWorkerBoundsConcurrencyAndCloseInterruptsIt() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(2, 1);
        try
        {
            queue.poll("a", seed -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); throw new CancellationException(); }
                return seed;
            }, 1);
            queue.replenish("a");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicInteger secondCalls = new AtomicInteger();
            queue.poll("b", seed -> { secondCalls.incrementAndGet(); return seed; }, 2);
            queue.replenish("b");
            assertEquals(0, secondCalls.get());
            queue.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertNull(queue.poll("b", seed -> seed, 3));
            assertEquals(0, queue.statistics()[4]);
            assertEquals(0, secondCalls.get());
        }
        finally { queue.close(); }
    }

    @Test public void primingCoversEachKeyBeforeExtraSlotsWithoutConsumingResults() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.List<String> calls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(2, 2))
        {
            assertTrue(queue.prime("a", seed -> {
                calls.add("a" + seed); entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new CancellationException(); }
                return seed;
            }, 10));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(queue.prime("b", seed -> { calls.add("b" + seed); return seed; }, 20));
            assertFalse(queue.firstLayoutsReady());
            release.countDown();
            await(queue::firstLayoutsReady);
            assertEquals(java.util.Arrays.asList("a10", "b20"), calls);
            assertEquals(1, queue.readyCount("a"));
            assertEquals(1, queue.readyCount("b"));
            assertArrayEquals(new long[]{0,0,0,2,2,2,0}, queue.statistics());
            queue.finishPriming();
            await(() -> queue.readyCount("a") == 2 && queue.readyCount("b") == 2);
            assertEquals(Long.valueOf(10), queue.poll("a", seed -> seed, 99));
        }
        finally { release.countDown(); }
    }

    @Test public void speculativePrimingDoesNotEvictOrWaitForBlockedWork() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 1))
        {
            queue.prime("a", seed -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new CancellationException(); }
                return seed;
            }, 7);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(queue.prime("b", seed -> seed, 9));
            ExecutorService waiter = Executors.newSingleThreadExecutor();
            try { waiter.submit(() -> { queue.awaitProgress(5); return null; }).get(2, TimeUnit.SECONDS); }
            finally { waiter.shutdownNow(); }
            assertFalse(queue.firstLayoutsReady());
            release.countDown();
            await(queue::firstLayoutsReady);
            assertEquals(Long.valueOf(7), queue.poll("a", seed -> seed, 99));
        }
        finally { release.countDown(); }
    }

    @Test public void failedPrimingDoesNotExtendReadinessWaitOrRetryContinuously() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 2))
        {
            queue.prime("bad", seed -> { calls.incrementAndGet(); throw new CancellationException(); }, 1);
            await(() -> queue.statistics()[2] == 1);
            assertTrue(queue.firstLayoutsReady());
            queue.finishPriming();
            assertEquals(1, calls.get());
            assertEquals(0, queue.readyCount("bad"));
        }
    }

    @Test public void actualDemandIsServedBeforeQueuedSpeculation() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(3, 1))
        {
            queue.prime("running", seed -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new CancellationException(); }
                return seed;
            }, 1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.prime("speculative", seed -> { order.add("speculative"); return seed; }, 2);
            queue.poll("demand", seed -> { order.add("demand"); return seed; }, 3);
            queue.replenish("demand");
            release.countDown();
            await(() -> queue.readyCount("demand") == 1 && queue.readyCount("speculative") == 1);
            assertEquals(java.util.Arrays.asList("demand", "speculative"), order);
        }
        finally { release.countDown(); }
    }

    @Test public void speculativeRetriesAreBoundedAndNormalRefillsUseNormalSupplier() throws Exception
    {
        AtomicInteger warmCalls = new AtomicInteger(), normalCalls = new AtomicInteger();
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 1))
        {
            queue.prime("a", seed -> { normalCalls.incrementAndGet(); return seed; }, seed -> {
                warmCalls.incrementAndGet(); throw new CancellationException();
            }, 10, 3);
            await(() -> queue.statistics()[2] == 3);
            assertTrue(queue.firstLayoutsReady());
            queue.finishPriming();
            assertEquals(3, warmCalls.get());
            assertEquals(0, normalCalls.get());
            assertNull(queue.poll("a", seed -> seed, 90));
            queue.replenish("a");
            await(() -> queue.readyCount("a") == 1);
            assertEquals(Long.valueOf(13), queue.poll("a", seed -> seed, 90));
            assertEquals(1, normalCalls.get());
            assertEquals(3, warmCalls.get());
        }
    }

    @Test public void endingDiscoveryPreservesInitialRetriesThenFillsToDepth() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger warmCalls = new AtomicInteger(), normalCalls = new AtomicInteger();
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 2))
        {
            queue.prime("a", seed -> { normalCalls.incrementAndGet(); return seed; }, seed -> {
                int attempt = warmCalls.incrementAndGet();
                if (attempt == 1)
                {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { throw new CancellationException(); }
                }
                if (attempt < 3) throw new CancellationException();
                return seed;
            }, 10, 3);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.finishPriming();
            assertFalse(queue.firstLayoutsReady());
            release.countDown();
            await(() -> queue.readyCount("a") == 2);
            assertEquals(3, warmCalls.get());
            assertEquals(1, normalCalls.get());
            assertEquals(2, queue.statistics()[2]);
            assertEquals(Long.valueOf(12), queue.poll("a", seed -> seed, 99));
            assertEquals(Long.valueOf(13), queue.poll("a", seed -> seed, 99));
        }
        finally { release.countDown(); }
    }

    @Test public void endingDiscoveryStillStopsAtRetryLimitAndDemandCanRecover() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger warmCalls = new AtomicInteger(), normalCalls = new AtomicInteger();
        try (FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 1))
        {
            queue.prime("a", seed -> { normalCalls.incrementAndGet(); return seed; }, seed -> {
                if (warmCalls.incrementAndGet() == 1)
                {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { throw new CancellationException(); }
                }
                throw new CancellationException();
            }, 10, 3);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.finishPriming(); release.countDown();
            await(() -> queue.statistics()[2] == 3);
            assertTrue(queue.firstLayoutsReady());
            queue.finishPriming();
            assertEquals(3, warmCalls.get());
            assertEquals(0, normalCalls.get());
            assertNull(queue.poll("a", seed -> seed, 99));
            queue.replenish("a");
            await(() -> queue.readyCount("a") == 1);
            assertEquals(Long.valueOf(13), queue.poll("a", seed -> seed, 99));
            assertEquals(3, warmCalls.get());
            assertEquals(1, normalCalls.get());
        }
        finally { release.countDown(); }
    }

    @Test public void closeCancelsAnInitialAttemptAfterDiscoveryEnds() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        FreshLayoutQueue<String, Long> queue = new FreshLayoutQueue<>(1, 2);
        try
        {
            queue.prime("a", seed -> seed, seed -> {
                attempts.incrementAndGet(); entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); throw new CancellationException(); }
                return seed;
            }, 10, 3);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.finishPriming(); queue.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(1, attempts.get());
            assertEquals(0, queue.statistics()[4]);
        }
        finally { queue.close(); }
    }
}
