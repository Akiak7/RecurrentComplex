package ivorius.reccomplex.world.gen.feature;

import net.minecraft.util.math.ChunkPos;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class WorldGenStructuresLockingTest
{
    private Method lockChunks;
    private Method unlockChunks;
    private Map<?, ?> chunkLocks;

    @Before
    public void setUp() throws Exception
    {
        lockChunks = WorldGenStructures.class.getDeclaredMethod("lockChunks", Stream.class);
        lockChunks.setAccessible(true);

        unlockChunks = WorldGenStructures.class.getDeclaredMethod("unlockChunks", List.class);
        unlockChunks.setAccessible(true);

        Field chunkLocksField = WorldGenStructures.class.getDeclaredField("CHUNK_LOCKS");
        chunkLocksField.setAccessible(true);
        chunkLocks = (Map<?, ?>) chunkLocksField.get(null);
        chunkLocks.clear();
    }

    @After
    public void tearDown()
    {
        chunkLocks.clear();
    }

    @Test
    public void sameChunkLocksSerializeAccess() throws Exception
    {
        ChunkPos chunk = new ChunkPos(3, -7);
        Object firstLocks = lockChunks(chunk);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        boolean firstUnlocked = false;

        try
        {
            Future<?> second = executor.submit(() ->
            {
                started.countDown();
                Object locks = lockChunks(chunk);
                try
                {
                    secondAcquired.set(true);
                    acquired.countDown();
                    Assert.assertTrue("Second task should be released", releaseSecond.await(5, TimeUnit.SECONDS));
                }
                finally
                {
                    unlockChunks(locks);
                }
                return null;
            });

            Assert.assertTrue("Second task should start", started.await(5, TimeUnit.SECONDS));
            Thread.sleep(100L);
            Assert.assertFalse("Second task must wait while the first task holds the same chunk", secondAcquired.get());

            unlockChunks(firstLocks);
            firstUnlocked = true;

            Assert.assertTrue("Second task should acquire after first unlocks", acquired.await(5, TimeUnit.SECONDS));
            Assert.assertTrue("Second task should acquire after first unlocks", secondAcquired.get());
            releaseSecond.countDown();
            second.get(5, TimeUnit.SECONDS);
        }
        finally
        {
            if (!firstUnlocked)
                unlockChunks(firstLocks);

            releaseSecond.countDown();
            executor.shutdownNow();
            Assert.assertTrue("Executor should stop", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void overlappingMultiChunkLocksDoNotDeadlock() throws Exception
    {
        ChunkPos first = new ChunkPos(-4, 2);
        ChunkPos second = new ChunkPos(5, -8);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);

        try
        {
            Future<?> left = executor.submit(() ->
            {
                start.await();
                Object locks = lockChunks(first, second);
                try
                {
                    Thread.sleep(25L);
                }
                finally
                {
                    unlockChunks(locks);
                }
                return null;
            });

            Future<?> right = executor.submit(() ->
            {
                start.await();
                Object locks = lockChunks(second, first);
                try
                {
                    Thread.sleep(25L);
                }
                finally
                {
                    unlockChunks(locks);
                }
                return null;
            });

            left.get(5, TimeUnit.SECONDS);
            right.get(5, TimeUnit.SECONDS);
        }
        finally
        {
            executor.shutdownNow();
            Assert.assertTrue("Executor should stop", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void lockCleanupRemovesIdleHoldersAfterNormalRelease() throws Exception
    {
        Object locks = lockChunks(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(0, 0));
        Assert.assertEquals("Distinct locked chunks should have holders", 2, chunkLocks.size());

        unlockChunks(locks);

        Assert.assertTrue("Idle lock holders should be removed after normal release", chunkLocks.isEmpty());
    }

    private Object lockChunks(ChunkPos... chunks) throws Exception
    {
        return lockChunks.invoke(null, Stream.of(chunks));
    }

    private void unlockChunks(Object locks) throws Exception
    {
        unlockChunks.invoke(null, locks);
    }
}
