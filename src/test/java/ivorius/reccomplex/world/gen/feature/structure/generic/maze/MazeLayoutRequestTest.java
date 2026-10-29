package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.maze.components.*;
import ivorius.reccomplex.world.gen.feature.structure.VariableDomain;
import ivorius.reccomplex.world.gen.feature.structure.generic.Selection;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.MazeRuleRegistry;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.saved.MazeRuleConnect;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.saved.MazeRuleConnectAll;
import ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator;
import net.minecraft.init.Bootstrap;
import org.junit.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class MazeLayoutRequestTest
{
    @BeforeClass public static void bootstrap()
    {
        Bootstrap.register();
        MazeRuleRegistry.INSTANCE.register("connect", MazeRuleConnect.class);
        MazeRuleRegistry.INSTANCE.register("connectall", MazeRuleConnectAll.class);
    }

    @Test public void solvesDetachedCorridorWithEntranceAndExit()
    {
        Fixture f = new Fixture();
        MazeLayoutRequest request = f.request(20000);
        // Mutate every mutable caller-owned input after snapshot creation.
        f.script.mazeComponent.rooms.clear();
        f.base.rooms.clear();
        f.component.weight = 0;
        f.component.variableDomain.set("test", false);
        List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> result = request.solve(7);
        assertEquals(3, result.size());
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)), result.stream()
                .map(p -> p.shift().getCoordinate(0)).collect(Collectors.toSet()));
        assertTrue(result.stream().allMatch(p -> p.component().variableDomain.get("test")));
        assertTrue(result.stream().allMatch(p -> p.component().structureID.equals("corridor")));
        // A consumed result can be changed without affecting a later solve.
        result.get(0).component().variableDomain.set("test", false);
        assertTrue(request.solve(8).stream().allMatch(p -> p.component().variableDomain.get("test")));
    }

    @Test public void keyIncludesConstraintsWeightsVariablesTransformsAndBudget()
    {
        MazeLayoutRequest original = new Fixture().request(20000);
        assertEquals(original, new Fixture().request(20000));
        assertEquals(original.hashCode(), new Fixture().request(20000).hashCode());
        Fixture f = new Fixture(); f.component.weight = .5;
        assertNotEquals(original, f.request(20000));
        f = new Fixture(); f.component.variableDomain.set("test", false);
        assertNotEquals(original, f.request(20000));
        f = new Fixture(); f.component.transform = AxisAlignedTransform2D.R1;
        assertNotEquals(original, f.request(20000));
        f = new Fixture(); ((MazeRuleConnectAll) f.script.rules.get(0)).preventConnection = true;
        assertNotEquals(original, f.request(20000));
        f = new Fixture(); f.base.exits.clear();
        assertNotEquals(original, f.request(20000));
        assertNotEquals(original, new Fixture().request(1000));
        f = new Fixture(); f.component.structureID = "replacement";
        assertNotEquals(original, f.request(20000));
    }

    @Test public void eachSolveOwnsItsRoomsAndConnectors()
    {
        MazeLayoutRequest request = new Fixture().request(20000);
        MazeComponentStructure<Connector> a = request.solve(2).get(0).component();
        MazeComponentStructure<Connector> b = request.solve(2).get(0).component();
        assertNotSame(a, b);
        assertNotSame(a.rooms.iterator().next(), b.rooms.iterator().next());
        assertNotSame(a.exits.values().iterator().next(), b.exits.values().iterator().next());
    }

    @Test public void timeoutAndInterruptDiscardWork()
    {
        MazeLayoutRequest request = new Fixture().request(20000);
        Assert.assertThrows(CancellationException.class, () -> request.solve(1, 0));
        assertEquals(3, request.solve(1).size()); // The speculative deadline did not alter normal solving or the key.
        Assert.assertThrows(CancellationException.class, () -> new Fixture().request(0).solve(1));
        Thread.currentThread().interrupt();
        try { Assert.assertThrows(CancellationException.class, () -> new Fixture().request(20000).solve(1)); }
        finally { Thread.interrupted(); }
    }

    @Test public void unknownRuleSubclassesUseForegroundPath()
    {
        Fixture f = new Fixture();
        f.script.rules.add(new MazeRuleConnectAll() {});
        assertFalse(MazeLayoutRequest.supports(f.script));
    }

    @Test public void backgroundBudgetCanExceedForegroundBudgetWithoutChangingCompatibility()
    {
        MazeLayoutRequest request = new Fixture().request(20000);
        assertEquals(40000, request.backgroundTimeoutMillis(2f));
        assertEquals(20000, request.backgroundTimeoutMillis(1f));
        assertEquals(200000, request.backgroundTimeoutMillis(10f));
        MazeLayoutRequest zeroForeground = new Fixture().request(0);
        assertEquals(3, zeroForeground.solve(1, 20000).size()); // Explicit worker budget is not clamped to foreground.
        Assert.assertThrows(CancellationException.class, () -> zeroForeground.solve(1));
        assertEquals(new Fixture().request(0), zeroForeground);
    }

    static class Fixture
    {
        final WorldScriptMazeGenerator script = new WorldScriptMazeGenerator();
        final SetMazeComponent<Connector> base = new SetMazeComponent<>();
        final MazeComponentStructure<Connector> component;

        Fixture()
        {
            script.mazeID = "test";
            script.mazeComponent.rooms.clear();
            script.mazeComponent.rooms.add(new Selection.Area(true, new int[]{0,0,0}, new int[]{2,0,0}));
            script.mazeComponent.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(0,0,0), false, "Path", Collections.emptyList()));
            script.mazeComponent.exitPaths.add(new SavedMazePathConnection(0, new MazeRoom(2,0,0), true, "Path", Collections.emptyList()));
            script.rules.add(new MazeRuleConnectAll());
            ConnectorFactory factory = new ConnectorFactory();
            Connector wall = factory.get("Wall"), path = factory.get("Path");
            WorldScriptMazeGenerator.enclose(base, new MazeRoom(-1,-1,-1), new MazeRoom(3,1,1), wall);
            base.exits.put(new MazePassage(new MazeRoom(-1,0,0), new MazeRoom(0,0,0)), path);
            base.exits.put(new MazePassage(new MazeRoom(3,0,0), new MazeRoom(2,0,0)), path);
            WorldGenMaze.addExternalReachability(ImmutableMultimap.builder(), base.exits, Collections.singleton(wall))
                    .build().entries().forEach(e -> base.reachability.put(e.getKey(), e.getValue()));
            MazeRoom room = new MazeRoom(0,0,0);
            MazePassage left = new MazePassage(room, new MazeRoom(-1,0,0)), right = new MazePassage(room, new MazeRoom(1,0,0));
            Map<MazePassage, Connector> exits = new HashMap<>();
            exits.put(left, path); exits.put(right, path);
            SetMazeComponent<Connector> part = WorldGenMaze.createCompleteComponent(Collections.singleton(room), exits, wall);
            part.reachability.put(left, right); part.reachability.put(right, left);
            WorldGenMaze.addExternalReachability(ImmutableMultimap.builder(), part.exits, Collections.singleton(wall))
                    .build().entries().forEach(e -> part.reachability.put(e.getKey(), e.getValue()));
            component = new MazeComponentStructure<>(1, "corridor", new VariableDomain().set("test", true),
                    AxisAlignedTransform2D.R0, ImmutableSet.copyOf(part.rooms), ImmutableMap.copyOf(part.exits),
                    ImmutableMultimap.copyOf(part.reachability));
        }

        MazeLayoutRequest request(long timeout)
        {
            return new MazeLayoutRequest(script, base, Collections.singletonList(component), 9, timeout);
        }
    }
}
