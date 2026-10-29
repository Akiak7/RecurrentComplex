package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import ivorius.ivtoolkit.maze.components.*;
import ivorius.reccomplex.world.gen.feature.structure.VariableDomain;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.saved.MazeRuleConnect;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.saved.MazeRuleConnectAll;
import ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import net.minecraft.nbt.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** All environment-dependent choices are resolved by the caller. The snapshot contains
 * only NBT values; each solve reconstructs privately owned rooms, connectors and rules.
 * Full value equality prevents reuse across different constraints, weights or variables. */
public final class MazeLayoutRequest
{
    private final NBTTagCompound data = new NBTTagCompound();
    private final int hash;

    public static boolean supports(WorldScriptMazeGenerator script)
    {
        return script.rules.stream().allMatch(r -> r != null &&
                (r.getClass() == MazeRuleConnect.class || r.getClass() == MazeRuleConnectAll.class));
    }

    public MazeLayoutRequest(WorldScriptMazeGenerator script, MazeComponent<Connector> maze,
                             List<MazeComponentStructure<Connector>> components, int reverses, long timeout)
    {
        if (!supports(script)) throw new IllegalArgumentException("Unsupported maze rule");
        NBTTagCompound definition = new NBTTagCompound();
        script.writeToNBT(definition);
        data.setTag("definition", definition.copy());
        data.setTag("maze", writeComponent(maze));
        NBTTagList candidates = new NBTTagList();
        for (MazeComponentStructure<Connector> component : components)
        {
            NBTTagCompound tag = writeComponent(component);
            tag.setString("structure", component.structureID);
            tag.setDouble("weight", component.weight);
            tag.setInteger("rotation", component.transform.getRotation());
            tag.setBoolean("mirror", component.transform.isMirrorX());
            NBTTagCompound variables = new NBTTagCompound();
            component.variableDomain.writeToNBT(variables);
            tag.setTag("variables", variables);
            candidates.appendTag(tag);
        }
        data.setTag("components", candidates);
        data.setInteger("reverses", reverses);
        data.setLong("timeout", timeout);
        hash = data.hashCode();
    }

    public List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> solve(long seed)
    {
        return solve(seed, data.getLong("timeout"));
    }

    public long backgroundTimeoutMillis(float multiplier)
    {
        return (long) Math.ceil(Math.max(0, data.getLong("timeout")) * (double) multiplier);
    }

    /** An explicit worker deadline may exceed the foreground budget without changing the compatibility key. */
    public List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> solve(long seed, long attemptMillis)
    {
        long start = System.nanoTime();
        long timeout = TimeUnit.MILLISECONDS.toNanos(Math.max(0, attemptMillis));
        Runnable check = () -> {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - start >= timeout)
                throw new CancellationException("Background maze layout interrupted or timed out");
        };
        check.run();
        ConnectorFactory factory = new ConnectorFactory();
        NBTTagCompound definition = data.getCompoundTag("definition");
        WorldScriptMazeGenerator script = new WorldScriptMazeGenerator();
        // Only built-in rule classes are reconstructed; no live registry or environment access.
        script.mazeComponent.readFromNBT(definition.getCompoundTag("mazeComponent").copy());
        NBTTagList ruleTags = definition.getTagList("rules", 10);
        for (int i = 0; i < ruleTags.tagCount(); i++)
        {
            NBTTagCompound tag = ruleTags.getCompoundTagAt(i);
            MazeRule rule = "connectall".equals(tag.getString("type")) ? new MazeRuleConnectAll() : new MazeRuleConnect();
            rule.readFromNBT(tag.getCompoundTag("rule").copy());
            script.rules.add(rule);
        }
        List<MazeComponentStructure<Connector>> components = new ArrayList<>();
        NBTTagList candidates = data.getTagList("components", 10);
        for (int i = 0; i < candidates.tagCount(); i++)
        {
            NBTTagCompound tag = candidates.getCompoundTagAt(i);
            SetMazeComponent<Connector> component = readComponent(tag, factory);
            VariableDomain variables = new VariableDomain();
            variables.readFromNBT(tag.getCompoundTag("variables"));
            components.add(new MazeComponentStructure<>(tag.getDouble("weight"), tag.getString("structure"), variables,
                    AxisAlignedTransform2D.from(tag.getInteger("rotation"), tag.getBoolean("mirror")),
                    ImmutableSet.copyOf(component.rooms()), ImmutableMap.copyOf(component.exits()),
                    ImmutableMultimap.copyOf(component.reachability())));
        }
        SetMazeComponent<Connector> maze = readComponent(data.getCompoundTag("maze"), factory);
        Set<Connector> blocked = Collections.singleton(factory.get(ConnectorStrategy.DEFAULT_WALL));
        ConnectorStrategy strategy = new ConnectorStrategy() {
            @Override public float connect(MazePassage passage, Connector existing, Connector add)
            {
                check.run();
                return super.connect(passage, existing, add);
            }
        };
        List<MazePredicate<Connector>> predicates = script.rules.stream()
                .map(r -> r.build(script, blocked, factory, components, strategy))
                .filter(Objects::nonNull).collect(Collectors.toCollection(ArrayList::new));
        int[] higher = script.mazeComponent.rooms.boundsHigher();
        for (int i = 0; i < higher.length; i++) higher[i]++;
        predicates.add(new LimitAABBStrategy<>(higher));
        predicates.add(new BlockedConnectorStrategy<>(blocked));
        MazePredicateMany<Connector> guarded = new MazePredicateMany<Connector>(predicates) {
            @Override public boolean canPlace(MorphingMazeComponent<Connector> current, ShiftedMazeComponent<?, Connector> component)
            {
                check.run();
                return super.canPlace(current, component);
            }
            @Override public boolean isDirtyConnection(MazeRoom dest, MazeRoom source, Connector c)
            {
                check.run();
                return super.isDirtyConnection(dest, source, c);
            }
        };
        check.run();
        List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> result =
                MazeComponentConnector.connect(maze, components, strategy, guarded, new Random(seed), data.getInteger("reverses"));
        check.run();
        return result;
    }

    private static NBTTagCompound writeComponent(MazeComponent<Connector> component)
    {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList rooms = new NBTTagList();
        component.rooms().stream().sorted(Comparator.comparing(MazeRoom::toString))
                .forEach(r -> rooms.appendTag(new NBTTagIntArray(r.getCoordinates())));
        tag.setTag("rooms", rooms);
        NBTTagList exits = new NBTTagList();
        component.exits().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString())).forEach(e -> {
            NBTTagCompound exit = writePassage(e.getKey());
            exit.setString("connector", e.getValue().id);
            exits.appendTag(exit);
        });
        tag.setTag("exits", exits);
        NBTTagList reachability = new NBTTagList();
        component.reachability().entries().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString() + ":" + e.getValue().toString())).forEach(e -> {
                    NBTTagCompound link = new NBTTagCompound();
                    link.setTag("from", writePassage(e.getKey()));
                    link.setTag("to", writePassage(e.getValue()));
                    reachability.appendTag(link);
                });
        tag.setTag("reachability", reachability);
        return tag;
    }

    private static SetMazeComponent<Connector> readComponent(NBTTagCompound tag, ConnectorFactory factory)
    {
        SetMazeComponent<Connector> result = new SetMazeComponent<>();
        NBTTagList rooms = tag.getTagList("rooms", 11);
        for (int i = 0; i < rooms.tagCount(); i++) result.rooms.add(new MazeRoom(rooms.getIntArrayAt(i)));
        NBTTagList exits = tag.getTagList("exits", 10);
        for (int i = 0; i < exits.tagCount(); i++)
        {
            NBTTagCompound exit = exits.getCompoundTagAt(i);
            result.exits.put(readPassage(exit), factory.get(exit.getString("connector")));
        }
        NBTTagList links = tag.getTagList("reachability", 10);
        for (int i = 0; i < links.tagCount(); i++)
        {
            NBTTagCompound link = links.getCompoundTagAt(i);
            result.reachability.put(readPassage(link.getCompoundTag("from")), readPassage(link.getCompoundTag("to")));
        }
        return result;
    }

    private static NBTTagCompound writePassage(MazePassage passage)
    {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setIntArray("source", passage.getSource().getCoordinates());
        tag.setIntArray("dest", passage.getDest().getCoordinates());
        return tag;
    }

    private static MazePassage readPassage(NBTTagCompound tag)
    {
        return new MazePassage(new MazeRoom(tag.getIntArray("source")), new MazeRoom(tag.getIntArray("dest")));
    }

    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other)
    {
        return other instanceof MazeLayoutRequest && data.equals(((MazeLayoutRequest) other).data);
    }
}
