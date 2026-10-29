package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import org.apache.logging.log4j.Logger;
import org.junit.*;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FreshMazeLayoutsReportingTest
{
    private Logger previousLogger, logger;
    private int previousSize, previousCapacity;

    @BeforeClass public static void bootstrap() { MazeLayoutRequestTest.bootstrap(); }
    @Before public void setup()
    {
        FreshMazeLayouts.clear();
        previousLogger = RecurrentComplex.logger;
        previousSize = RCConfig.mazeLayoutQueueSize;
        previousCapacity = RCConfig.mazeLayoutQueueMaxConfigurations;
        RecurrentComplex.logger = logger = mock(Logger.class);
        RCConfig.mazeLayoutQueueSize = 1;
        RCConfig.mazeLayoutQueueMaxConfigurations = 16;
    }
    @After public void cleanup()
    {
        FreshMazeLayouts.clear();
        RecurrentComplex.logger = previousLogger;
        RCConfig.mazeLayoutQueueSize = previousSize;
        RCConfig.mazeLayoutQueueMaxConfigurations = previousCapacity;
    }

    private void await(boolean failure) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (FreshMazeLayouts.statistics()[failure ? 2 : 5] == 0)
        {
            if (System.nanoTime() >= deadline) fail("Worker did not reach expected state");
            FreshMazeLayouts.awaitProgress(10);
        }
    }

    private MazeLayoutRequest fill() throws Exception
    {
        MazeLayoutRequest request = new MazeLayoutRequestTest.Fixture().request(20000);
        assertNull(FreshMazeLayouts.poll(request, 10));
        FreshMazeLayouts.replenish(request);
        await(false);
        return request;
    }

    @Test public void periodicSummaryCountsHitsAndFallbacksWithoutChangingQueue() throws Exception
    {
        MazeLayoutRequest request = fill();
        assertNotNull(FreshMazeLayouts.poll(request, 20));
        long[] before = FreshMazeLayouts.statistics();
        long now = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        FreshMazeLayouts.reportIfDue(now);
        FreshMazeLayouts.reportIfDue(now + TimeUnit.MINUTES.toNanos(1));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(logger, times(1)).info(text.capture());
        assertTrue(text.getValue().contains("eligible requests=2"));
        assertTrue(text.getValue().contains("ready hits=1 (50.0%)"));
        assertTrue(text.getValue().contains("slow fallbacks=1"));
        assertTrue(text.getValue().contains("configuration capacity=16, cached configurations=1, ready layouts=0, evictions=0"));
        assertArrayEquals(before, FreshMazeLayouts.statistics());
    }

    @Test public void resetFlushesCountersChangedSincePeriodicReportAndDoesNotDoubleReport() throws Exception
    {
        MazeLayoutRequest request = fill();
        long now = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        FreshMazeLayouts.reportIfDue(now);
        assertNotNull(FreshMazeLayouts.poll(request, 20));
        FreshMazeLayouts.reportIfDue(now + TimeUnit.SECONDS.toNanos(1));
        verify(logger, times(1)).info(anyString());
        FreshMazeLayouts.clear("server stopping");
        FreshMazeLayouts.clear("server stopped");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(logger, times(2)).info(text.capture());
        assertTrue(text.getAllValues().get(1).contains("(server stopping)"));
        assertTrue(text.getAllValues().get(1).contains("eligible requests=2"));
        fill();
        FreshMazeLayouts.reportIfDue(System.nanoTime() + TimeUnit.MINUTES.toNanos(2));
        verify(logger, times(3)).info(text.capture());
        assertTrue(text.getValue().contains("eligible requests=1"));
    }

    @Test public void unusedPrimedQueueAndDisabledQueueDoNotProducePeriodicNoise() throws Exception
    {
        FreshMazeLayouts.reportIfDue(System.nanoTime() + TimeUnit.MINUTES.toNanos(2));
        MazeLayoutRequest request = new MazeLayoutRequestTest.Fixture().request(20000);
        FreshMazeLayouts.prime(request, 10);
        await(false);
        FreshMazeLayouts.reportIfDue(System.nanoTime() + TimeUnit.MINUTES.toNanos(2));
        FreshMazeLayouts.clear("configuration reload");
        verifyZeroInteractions(logger);
    }

    @Test public void summaryIncludesDiscardedBackgroundAttempts() throws Exception
    {
        MazeLayoutRequest request = new MazeLayoutRequestTest.Fixture().request(0);
        FreshMazeLayouts.poll(request, 10);
        FreshMazeLayouts.replenish(request);
        await(true);
        FreshMazeLayouts.clear("server stopping");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(logger).info(text.capture());
        assertTrue(text.getValue().contains("discarded background attempts=1"));
    }

    @Test public void capacityWarningOccursOncePerResetAndSummaryReportsEvictions()
    {
        RCConfig.mazeLayoutQueueMaxConfigurations = 1;
        for (int i = 0; i < 3; i++)
            FreshMazeLayouts.poll(new MazeLayoutRequestTest.Fixture().request(1000 + i), i);
        verify(logger, times(1)).warn(contains("configuration capacity"), eq(1));
        FreshMazeLayouts.clear("configuration reload");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(logger).info(text.capture());
        assertTrue(text.getValue().contains("evictions=2"));
        FreshMazeLayouts.poll(new MazeLayoutRequestTest.Fixture().request(1000), 0);
        FreshMazeLayouts.poll(new MazeLayoutRequestTest.Fixture().request(1001), 1);
        verify(logger, times(2)).warn(contains("configuration capacity"), eq(1));
    }

    @Test public void unlimitedCapacityIsReportedAndDoesNotWarn()
    {
        RCConfig.mazeLayoutQueueMaxConfigurations = 0;
        for (int i = 0; i < 20; i++)
            FreshMazeLayouts.poll(new MazeLayoutRequestTest.Fixture().request(1000 + i), i);
        FreshMazeLayouts.clear("server stopping");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(logger).info(text.capture());
        assertTrue(text.getValue().contains("configuration capacity=unlimited, cached configurations=20, ready layouts=0, evictions=0"));
        verify(logger, never()).warn(anyString(), anyInt());
    }
}
