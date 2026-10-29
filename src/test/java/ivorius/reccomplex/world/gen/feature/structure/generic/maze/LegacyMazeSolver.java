package ivorius.reccomplex.world.gen.feature.structure.generic.maze;

import com.google.common.collect.ImmutableListMultimap;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.math.IvVecMathHelper;
import ivorius.ivtoolkit.maze.components.*;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.MazeGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.BlockedConnectorStrategy;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.LimitAABBStrategy;
import ivorius.reccomplex.world.gen.feature.structure.generic.maze.rules.MazeRule;
import ivorius.reccomplex.world.gen.script.WorldScriptMazeGenerator;
import net.minecraft.util.math.MathHelper;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** Frozen pre-queue getPlacedRooms implementation from revision 722b9601.
 * Its method body is intentionally unchanged: this is the disabled-mode behavior oracle. */
final class LegacyMazeSolver extends WorldScriptMazeGenerator
{
    public List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>> getPlacedRooms(Random random, AxisAlignedTransform2D transform, Environment environment)
    {
        if (mazeComponent.rooms.isEmpty())
            return null;

        ConnectorFactory factory = new ConnectorFactory();

        Connector wallConnector = factory.get(ConnectorStrategy.DEFAULT_WALL);
        Connector defaultConnector = mazeComponent.defaultConnector.toConnector(factory);
        Set<Connector> blockedConnections = Collections.singleton(wallConnector); // TODO Make configurable

        int[] boundsHigher = mazeComponent.rooms.boundsHigher();
        int[] boundsLower = mazeComponent.rooms.boundsLower();

        int[] oneArray = new int[boundsHigher.length];
        Arrays.fill(oneArray, 1);

        final int[] outsideBoundsHigher = IvVecMathHelper.add(boundsHigher, oneArray);
        final int[] outsideBoundsLower = IvVecMathHelper.sub(boundsLower, oneArray);

        List<MazeComponentStructure<Connector>> transformedComponents = MazeGeneration.structures(StructureRegistry.INSTANCE, mazeID)
                .flatMap(pair -> pair.getLeft().declaredVariables().omega(environment, true)
                        .flatMap(domain -> WorldGenMaze.transforms(pair.getLeft(), pair.getRight(), transform, factory, environment.copy(domain), blockedConnections))
                )
                .collect(Collectors.toList());

        MorphingMazeComponent<Connector> maze = new SetMazeComponent<>();

        WorldScriptMazeGenerator.enclose(maze, new MazeRoom(outsideBoundsLower), new MazeRoom(outsideBoundsHigher), defaultConnector);
        WorldScriptMazeGenerator.blockRooms(maze, mazeComponent.rooms.compile(false).keySet(), defaultConnector);

        WorldGenMaze.buildExitPaths(environment, factory, mazeComponent.exitPaths, maze.rooms()).forEach(path -> maze.exits().put(path.getKey(), path.getValue()));
        // Add reachability outside the maze
        maze.reachability().putAll(WorldGenMaze.addExternalReachability(ImmutableListMultimap.builder(), maze.exits(), blockedConnections).build());

        // Add reachability between exits
        maze.reachability().putAll(mazeComponent.reachability.build(ImmutableListMultimap.builder(), AxisAlignedTransform2D.ORIGINAL, mazeComponent.boundsSize(), SavedMazeReachability.notBlocked(blockedConnections, maze.exits()), maze.exits().keySet()).build());

        ConnectorStrategy connectorStrategy = new ConnectorStrategy();

        List<MazePredicate<Connector>> predicates = rules.stream().map(r -> r.build(this, blockedConnections, factory, transformedComponents, connectorStrategy)).filter(Objects::nonNull).collect(Collectors.toCollection(ArrayList::new));
        predicates.add(new LimitAABBStrategy<>(outsideBoundsHigher));
        predicates.add(new BlockedConnectorStrategy<>(blockedConnections));

        int totalRooms = mazeComponent.rooms.compile(true).size();

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<List<PlacedMazeComponent<MazeComponentStructure<Connector>, Connector>>> future = executor.submit(() -> MazeComponentConnector.connect(maze,
                transformedComponents, connectorStrategy,
                new MazePredicateMany<>(predicates),
                random,
                RCConfig.mazePlacementReversesPerRoom >= 0 ? MathHelper.floor(totalRooms * RCConfig.mazePlacementReversesPerRoom + 0.5f) : MazeComponentConnector.INFINITE_REVERSES
        ));
        executor.shutdown();

        try
        {
            return future.get(RCConfig.mazeTimeout, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            future.cancel(true);
            throw new GenerationException("Maze generation timed out: " + mazeID);
        }
        catch (ExecutionException | InterruptedException e)
        {
            Throwable t = e.getCause();

            if (t instanceof Error) throw (Error) t;
            else throw new GenerationException("Error generating maze; " + mazeID, t);
        }
    }
}
