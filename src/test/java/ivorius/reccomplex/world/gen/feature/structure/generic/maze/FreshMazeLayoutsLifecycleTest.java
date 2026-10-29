package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.files.loading.LeveledRegistry;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import org.junit.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class FreshMazeLayoutsLifecycleTest
{
    private int previousSize;

    @BeforeClass public static void bootstrap() { MazeLayoutRequestTest.bootstrap(); }
    @Before public void setup()
    {
        previousSize = RCConfig.mazeLayoutQueueSize;
        RCConfig.mazeLayoutQueueSize = 1;
        FreshMazeLayouts.clear();
    }
    @After public void cleanup()
    {
        FreshMazeLayouts.clear();
        RCConfig.mazeLayoutQueueSize = previousSize;
    }

    private MazeLayoutRequest fill() throws Exception
    {
        MazeLayoutRequest request = new MazeLayoutRequestTest.Fixture().request(20000);
        assertNull(FreshMazeLayouts.poll(request, 10));
        FreshMazeLayouts.replenish(request);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (FreshMazeLayouts.statistics()[5] == 0)
        {
            if (System.nanoTime() >= deadline) fail("No layout produced");
            Thread.sleep(1);
        }
        return request;
    }

    @Test public void sessionResetDiscardsReadyLayoutsAndStartsANewQueue() throws Exception
    {
        fill();
        FreshMazeLayouts.clear();
        assertArrayEquals(new long[7], FreshMazeLayouts.statistics());
        MazeLayoutRequest request = fill();
        assertNotNull(FreshMazeLayouts.poll(request, 20));
        assertEquals(1, FreshMazeLayouts.statistics()[0]);
        assertEquals(1, FreshMazeLayouts.statistics()[1]);
    }

    @Test public void registryChangesDiscardLayoutsPreparedAgainstOldDefinitions() throws Exception
    {
        fill();
        String id = "maze-queue-lifecycle-test";
        try
        {
            StructureRegistry.INSTANCE.register(id, "test", mock(Structure.class), false, LeveledRegistry.Level.CUSTOM);
            assertArrayEquals(new long[7], FreshMazeLayouts.statistics());
        }
        finally { StructureRegistry.INSTANCE.unregister(id, LeveledRegistry.Level.CUSTOM); }
    }
}
