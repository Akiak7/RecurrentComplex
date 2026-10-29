package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.maze.components.MazeRoom;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.files.loading.LeveledRegistry;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericStructure;
import ivorius.reccomplex.world.gen.feature.structure.generic.Selection;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.MazeGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.NaturalGeneration;
import net.minecraft.init.Biomes;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.junit.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

public class MazeLayoutWarmupTest
{
    private boolean enabled;
    private int budget, capacity;
    private long timeout;
    private float reverses;

    @BeforeClass public static void bootstrap() { MazeLayoutRequestTest.bootstrap(); }
    @Before public void setup()
    {
        enabled = RCConfig.mazeLayoutQueue; budget = RCConfig.mazeLayoutWarmupMillis;
        capacity = RCConfig.mazeLayoutQueueMaxConfigurations;
        timeout = RCConfig.mazeTimeout; reverses = RCConfig.mazePlacementReversesPerRoom;
        RCConfig.mazeTimeout = 20000; RCConfig.mazePlacementReversesPerRoom = 3;
        MazeLayoutWarmup.stop(); FreshMazeLayouts.clear();
    }
    @After public void cleanup()
    {
        MazeLayoutWarmup.stop(); FreshMazeLayouts.clear();
        RCConfig.mazeLayoutQueue = enabled; RCConfig.mazeLayoutWarmupMillis = budget;
        RCConfig.mazeTimeout = timeout; RCConfig.mazePlacementReversesPerRoom = reverses;
        RCConfig.mazeLayoutQueueMaxConfigurations = capacity;
    }

    private GenericStructure parent()
    {
        GenericStructure parent = new GenericStructure();
        parent.rotatable = true;
        NaturalGeneration generation = mock(NaturalGeneration.class);
        when(generation.getGenerationWeight(any(), any())).thenReturn(1d);
        parent.generationTypes.add(generation);
        NBTTagCompound definition = new NBTTagCompound();
        new MazeLayoutRequestTest.Fixture().script.writeToNBT(definition);
        NBTTagCompound tile = new NBTTagCompound(); tile.setTag("script", definition);
        NBTTagList tiles = new NBTTagList(); tiles.appendTag(tile);
        parent.worldDataCompound = new NBTTagCompound(); parent.worldDataCompound.setTag("tileEntities", tiles);
        return parent;
    }

    private WorldServer world()
    {
        WorldServer world = mock(WorldServer.class);
        when(world.getSpawnPoint()).thenReturn(BlockPos.ORIGIN);
        when(world.getBiome(BlockPos.ORIGIN)).thenReturn(Biomes.DESERT);
        return world;
    }

    private GenericStructure corridorPiece()
    {
        GenericStructure piece = new GenericStructure();
        SavedMazeComponent component = new SavedMazeComponent("Wall");
        component.rooms.add(new Selection.Area(true, new int[]{0,0,0}, new int[]{0,0,0}));
        component.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(0,0,0), false, "Path", Collections.emptyList()));
        component.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(0,0,0), true, "Path", Collections.emptyList()));
        piece.generationTypes.add(new MazeGeneration("corridor", 1d, "test", component));
        return piece;
    }

    @Test public void discoveryPrioritizesSpawnBiomeAndFindsAllAllowedRotations()
    {
        GenericStructure parent = parent(); WorldServer world = world();
        List<MazeLayoutWarmup.Candidate> candidates = MazeLayoutWarmup.discover(Collections.singletonList(parent),
                Collections.singletonList(world), Arrays.asList(Biomes.PLAINS, Biomes.DESERT));
        assertEquals(8, candidates.size());
        for (int i = 0; i < 4; i++) assertSame(Biomes.DESERT, candidates.get(i).biome);
        assertEquals(AxisAlignedTransform2D.R0, candidates.get(0).transform);
        assertEquals("test", candidates.get(0).script.mazeID);
        verify(world, never()).getChunkFromChunkCoords(anyInt(), anyInt());
        verify(world, never()).getBlockState(any());
    }

    @Test public void discoverySkipsDisabledTilesAndBlockPayloads()
    {
        GenericStructure parent = parent();
        parent.worldDataCompound.getTagList("tileEntities", 10).getCompoundTagAt(0).setBoolean("spawnTriggerable", false);
        assertTrue(MazeLayoutWarmup.scripts(parent.worldDataCompound).isEmpty());
        NBTTagCompound data = new NBTTagCompound(); data.setTag("blockCollection", parent().worldDataCompound);
        assertTrue(MazeLayoutWarmup.scripts(data).isEmpty());
    }

    @Test public void disabledAndZeroBudgetWarmupDoNotInspectWorldsOrCreateQueue()
    {
        MinecraftServer server = mock(MinecraftServer.class);
        RCConfig.mazeLayoutQueue = false;
        MazeLayoutWarmup.start(server);
        assertArrayEquals(new long[3], MazeLayoutWarmup.startupStatistics());
        RCConfig.mazeLayoutQueue = true; RCConfig.mazeLayoutWarmupMillis = 0;
        MazeLayoutWarmup.start(server);
        assertArrayEquals(new long[3], MazeLayoutWarmup.startupStatistics());
        assertArrayEquals(new long[7], FreshMazeLayouts.statistics());
        verifyZeroInteractions(server);
    }

    @Test public void discoveredRequestMatchesRuntimeRequestAndIsConsumedOnFirstEncounter() throws Exception
    {
        String id = "warmup-corridor-test";
        GenericStructure piece = new GenericStructure();
        SavedMazeComponent component = new SavedMazeComponent("Wall");
        component.rooms.add(new Selection.Area(true, new int[]{0,0,0}, new int[]{0,0,0}));
        component.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(0,0,0), false, "Path", Collections.emptyList()));
        component.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(0,0,0), true, "Path", Collections.emptyList()));
        piece.generationTypes.add(new MazeGeneration("corridor", 1d, "test", component));
        try
        {
            StructureRegistry.INSTANCE.register(id, "test", piece, true, LeveledRegistry.Level.CUSTOM);
            GenericStructure parent = parent(); WorldServer world = world();
            MazeLayoutWarmup.Candidate candidate = MazeLayoutWarmup.discover(Collections.singletonList(parent),
                    Collections.singletonList(world), Collections.singletonList(Biomes.DESERT)).get(0);
            MazeLayoutRequest request = candidate.request();
            assertNotNull(request);
            Environment environment = new Environment(world, Biomes.DESERT, null, candidate.generation);
            assertEquals(request, candidate.script.createLayoutRequest(AxisAlignedTransform2D.R0, environment));
            RCConfig.mazeLayoutQueue = true;
            assertTrue(FreshMazeLayouts.prime(request, 10));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!FreshMazeLayouts.firstLayoutsReady())
            {
                if (System.nanoTime() >= deadline) fail("Warmup did not finish");
                FreshMazeLayouts.awaitProgress(10);
            }
            assertEquals(3, candidate.script.getPlacedRooms(new Random(9), AxisAlignedTransform2D.R0, environment).size());
            assertEquals(1, FreshMazeLayouts.statistics()[0]);
            assertEquals(0, FreshMazeLayouts.statistics()[1]);
            verify(world, never()).getChunkFromChunkCoords(anyInt(), anyInt());
            verify(world, never()).getBlockState(any());
        }
        finally { StructureRegistry.INSTANCE.unregister(id, LeveledRegistry.Level.CUSTOM); }
    }

    @Test public void zeroStartupWaitPreparesLayoutsOnTicksBeforeAnyEncounter() throws Exception
    {
        String pieceID = "background-piece-test", parentID = "background-parent-test";
        try
        {
            StructureRegistry.INSTANCE.register(pieceID, "test", corridorPiece(), true, LeveledRegistry.Level.CUSTOM);
            StructureRegistry.INSTANCE.register(parentID, "test", parent(), true, LeveledRegistry.Level.CUSTOM);
            MinecraftServer server = mock(MinecraftServer.class);
            server.worlds = new WorldServer[]{world()};
            RCConfig.mazeLayoutQueue = true;
            RCConfig.mazeLayoutWarmupMillis = 0;
            MazeLayoutWarmup.start(server);
            assertArrayEquals(new long[7], FreshMazeLayouts.statistics());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (FreshMazeLayouts.statistics()[5] == 0)
            {
                if (System.nanoTime() >= deadline) fail("No background layout before demand");
                MazeLayoutWarmup.tick();
                FreshMazeLayouts.awaitProgress(10);
            }
            assertEquals(0, FreshMazeLayouts.statistics()[0]);
            assertEquals(0, FreshMazeLayouts.statistics()[1]);
            assertArrayEquals(new long[3], MazeLayoutWarmup.startupStatistics());
        }
        finally
        {
            StructureRegistry.INSTANCE.unregister(parentID, LeveledRegistry.Level.CUSTOM);
            StructureRegistry.INSTANCE.unregister(pieceID, LeveledRegistry.Level.CUSTOM);
        }
    }

    @Test public void preparationUsesConfiguredCapacityIncludingUnlimited()
    {
        String id = "discovery-many-configurations-test";
        try
        {
            StructureRegistry.INSTANCE.register(id, "test", corridorPiece(), true, LeveledRegistry.Level.CUSTOM);
            List<Structure<?>> parents = new ArrayList<>();
            for (int i = 0; i < 20; i++)
            {
                GenericStructure parent = parent(); parent.rotatable = false;
                parent.worldDataCompound.getTagList("tileEntities", 10).getCompoundTagAt(0)
                        .getCompoundTag("script").setIntArray("roomSize", new int[]{i + 1, 1, 1});
                parents.add(parent);
            }
            MazeLayoutWarmup.Discovery discovery = MazeLayoutWarmup.discover(parents,
                    Collections.singletonList(world()), Collections.singletonList(Biomes.DESERT), Long.MAX_VALUE);
            assertEquals(20, discovery.definitions);
            assertFalse(discovery.truncated);
            for (int limit : new int[]{3, 18, 0})
            {
                FreshMazeLayouts.clear();
                RCConfig.mazeLayoutQueueMaxConfigurations = limit;
                MazeLayoutWarmup.Session session = new MazeLayoutWarmup.Session(discovery);
                while (session.hasNext()) session.step();
                session.finish();
                assertEquals(limit == 0 ? 20 : limit, FreshMazeLayouts.statistics()[4]);
            }
            MazeLayoutWarmup.Discovery cutoff = MazeLayoutWarmup.discover(parents,
                    Collections.singletonList(world()), Collections.singletonList(Biomes.DESERT), 0);
            assertTrue(cutoff.truncated);
            assertEquals(0, cutoff.definitions);
        }
        finally { StructureRegistry.INSTANCE.unregister(id, LeveledRegistry.Level.CUSTOM); }
    }
}
