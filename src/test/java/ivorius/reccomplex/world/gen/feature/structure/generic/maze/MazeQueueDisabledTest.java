package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.maze.components.*;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.files.loading.LeveledRegistry;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericStructure;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericVariableDomain;
import ivorius.reccomplex.world.gen.feature.structure.generic.Selection;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.MazeGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.saved.MazeRuleConnectAll;
import ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator;
import net.minecraft.init.Biomes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import org.junit.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class MazeQueueDisabledTest
{
    private boolean enabled;
    private long timeout;
    private float reverses;
    private final String mazeID = "queue-disabled-parity-test";

    @BeforeClass public static void bootstrap() { MazeLayoutRequestTest.bootstrap(); }
    @Before public void setup()
    {
        enabled = RCConfig.mazeLayoutQueue;
        timeout = RCConfig.mazeTimeout; reverses = RCConfig.mazePlacementReversesPerRoom;
        RCConfig.mazeLayoutQueue = false; RCConfig.mazeTimeout = 20000; RCConfig.mazePlacementReversesPerRoom = 3;
        FreshMazeLayouts.clear();
        for (int i = 0; i < 2; i++)
        {
            GenericStructure piece = new GenericStructure();
            piece.rotatable = true; piece.mirrorable = true;
            GenericVariableDomain.Variable variable = new GenericVariableDomain.Variable();
            variable.id = "choice"; variable.chance = i; variable.affectsLogic = true;
            piece.variableDomain.variables().add(variable);
            SavedMazeComponent component = new SavedMazeComponent("Wall");
            component.rooms.add(new Selection.Area(true, new int[]{0,0,0}, new int[]{0,0,0}));
            component.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(0,0,0), false, "Path", Collections.emptyList()));
            component.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(0,0,0), true, "Path", Collections.emptyList()));
            piece.generationTypes.add(new MazeGeneration("piece", i + 1d, mazeID, component));
            StructureRegistry.INSTANCE.register(mazeID + i, "test", piece, true, LeveledRegistry.Level.CUSTOM);
        }
    }
    @After public void cleanup()
    {
        for (int i = 0; i < 2; i++) StructureRegistry.INSTANCE.unregister(mazeID + i, LeveledRegistry.Level.CUSTOM);
        FreshMazeLayouts.clear();
        RCConfig.mazeLayoutQueue = enabled; RCConfig.mazeTimeout = timeout; RCConfig.mazePlacementReversesPerRoom = reverses;
    }

    private WorldScriptMazeGenerator script(boolean legacy)
    {
        WorldScriptMazeGenerator script = legacy ? new LegacyMazeSolver() : new WorldScriptMazeGenerator();
        NBTTagCompound definition = new NBTTagCompound();
        new MazeLayoutRequestTest.Fixture().script.writeToNBT(definition);
        script.readFromNBT(definition);
        script.mazeID = mazeID;
        return script;
    }

    private Environment environment()
    {
        return new Environment(mock(WorldServer.class), Biomes.DESERT, null, null);
    }

    private List<String> layout(List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> placed)
    {
        return placed.stream().map(p -> p.component().structureID + ":" + p.shift()
                + ":" + p.component().transform.getRotation() + ":" + p.component().transform.isMirrorX()
                + ":" + p.component().variableDomain.get("choice")).collect(Collectors.toList());
    }

    private Set<Long> workers()
    {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                && t.getName().equals("RecurrentComplex-maze-layouts")).map(Thread::getId).collect(Collectors.toSet());
    }

    @Test public void disabledQueueMatchesOriginalLayoutsAndRandomStateAcrossSeedsAndTransforms()
    {
        Set<Long> before = workers();
        for (int mirror = 0; mirror < 2; mirror++) for (int rotation = 0; rotation < 4; rotation++)
            for (long seed : new long[]{0, 1, 42, -7})
            {
                AxisAlignedTransform2D transform = AxisAlignedTransform2D.from(rotation, mirror != 0);
                Random expectedRandom = new Random(seed), actualRandom = new Random(seed);
                List<String> expected = layout(script(true).getPlacedRooms(expectedRandom, transform, environment()));
                List<String> actual = layout(script(false).getPlacedRooms(actualRandom, transform, environment()));
                assertEquals("seed=" + seed + " rotation=" + rotation + " mirror=" + mirror, expected, actual);
                assertEquals(expectedRandom.nextLong(), actualRandom.nextLong());
            }
        assertArrayEquals(new long[7], FreshMazeLayouts.statistics());
        assertTrue(before.containsAll(workers())); // No new background layout worker.
    }

    @Test public void customRulesStillRunInDisabledMode()
    {
        AtomicInteger builds = new AtomicInteger();
        WorldScriptMazeGenerator current = script(false);
        current.rules.add(new MazeRuleConnectAll() {
            @Override public MazePredicate<Connector> build(
                    WorldScriptMazeGenerator script, Set<Connector> blocked, ConnectorFactory factory,
                    Collection<? extends MazeComponent<Connector>> components, ConnectionStrategy<Connector> strategy)
            {
                builds.incrementAndGet();
                return super.build(script, blocked, factory, components, strategy);
            }
        });
        assertEquals(3, current.getPlacedRooms(new Random(1), AxisAlignedTransform2D.R0, environment()).size());
        assertEquals(1, builds.get());
        assertArrayEquals(new long[7], FreshMazeLayouts.statistics());
    }
}
