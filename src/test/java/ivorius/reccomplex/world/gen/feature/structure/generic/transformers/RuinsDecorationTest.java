package ivorius.reccomplex.world.gen.feature.structure.generic.transformers;

import ivorius.ivtoolkit.blocks.BlockAreas;
import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.tools.IvWorldData;
import ivorius.ivtoolkit.world.chunk.gen.StructureBoundingBoxes;
import ivorius.reccomplex.world.gen.feature.structure.Structures;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.events.handlers.RCForgeEventHandler;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.junit.*;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RuinsDecorationTest
{
    private RCForgeEventHandler previousHandler;

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }
    @Before public void installHandler()
    {
        previousHandler = RecurrentComplex.forgeEventHandler;
        RecurrentComplex.forgeEventHandler = new RCForgeEventHandler();
    }
    @After public void restoreHandler() { RecurrentComplex.forgeEventHandler = previousHandler; }

    @Test public void cobwebOnlyDecoratesAnEligibleCorner()
    {
        Fixture f = new Fixture(0, 0, 1, true);
        f.run(null, false);
        assertEquals(Blocks.WEB.getDefaultState(), f.blocks.get(f.corner));
        assertFalse(f.writes.isEmpty());
    }

    @Test public void cobwebOnlyLeavesOpenAirUnchanged()
    {
        Fixture f = new Fixture(0, 0, 1, false);
        f.run(null, false);
        assertTrue(f.writes.isEmpty());
        assertTrue(f.trace.stream().anyMatch(s -> s.startsWith("random:")));
    }

    @Test public void allEffectsDisabledAvoidWorldAndRandomAccess()
    {
        Fixture f = new Fixture(0, 0, 0, true);
        f.run(null, false);
        assertTrue(f.trace.isEmpty());
    }

    @Test public void decayExcludedPositionsAreNotDecorated()
    {
        Fixture f = new Fixture(0, 0, 1, true);
        f.instance.baseDecay = 2.0;
        f.run(null, false);
        assertTrue(f.writes.isEmpty());
        assertFalse(f.trace.stream().anyMatch(s -> s.startsWith("random:")));
    }

    @Test public void cobwebOnlyRespectsInitialAndCompletionClips()
    {
        Fixture f = new Fixture(0, 0, 1, true);
        StructureBoundingBox first = f.clip(0, 0);
        f.run(first, false);
        assertFalse(f.blocks.containsKey(f.corner));
        assertTrue(f.writes.stream().allMatch(first::isVecInside));
        f.writes.clear();
        StructureBoundingBox completion = f.clip(1, 2);
        f.run(completion, true);
        assertEquals(Blocks.WEB.getDefaultState(), f.blocks.get(f.corner));
        assertTrue(f.writes.stream().allMatch(completion::isVecInside));
    }

    @Test public void existingErosionAndVinePassesMatchOriginalOrderedOperations()
    {
        for (float[] effects : new float[][]{{0.7f, 0, 0}, {0, 0.6f, 0}, {0.7f, 0.6f, 0.8f}})
            for (int rotation = 0; rotation < 4; rotation++)
                for (boolean mirror : new boolean[]{false, true})
                {
                    Fixture expected = new Fixture(effects[0], effects[1], effects[2], true);
                    Fixture actual = new Fixture(effects[0], effects[1], effects[2], true);
                    expected.transform = actual.transform = AxisAlignedTransform2D.from(rotation, mirror);
                    for (int pass = 0; pass < 2; pass++)
                    {
                        StructureSpawnContext referenceContext = expected.context(expected.clip(pass == 0 ? 0 : 1, pass == 0 ? 0 : 2), pass > 0);
                        legacyDecorationPass(expected.ruins, expected.instance, referenceContext, expected.data, expected.run);
                        actual.run(actual.clip(pass == 0 ? 0 : 1, pass == 0 ? 0 : 2), pass > 0);
                        assertEquals(expected.trace, actual.trace);
                        assertEquals(expected.blocks, actual.blocks);
                    }
                }
    }

    // Frozen pre-fix AFTER decoration dispatch. Gravity is disabled and fallingBlocks is empty
    // in these fixtures. The unchanged decayBlock implementation supplies the block operations.
    private static void legacyDecorationPass(TransformerRuins ruins, TransformerRuins.InstanceData instance,
                                             StructureSpawnContext context, IvWorldData worldData, RunTransformer transformer)
    {
        assertTrue(instance.fallingBlocks.isEmpty());
        if (ruins.blockErosion > 0.0f || ruins.vineGrowth > 0.0f)
        {
            IvBlockCollection collection = worldData.blockCollection;
            int[] areaSize = {collection.width, collection.height, collection.length};
            StructureBoundingBox relevantBB = context.generationBB != null
                    ? Structures.intersection(context.boundingBox, context.generationBB) : context.boundingBox;
            for (BlockPos sourceCoord : BlockAreas.mutablePositions(collection.area()))
            {
                BlockPos worldCoord = context.transform.apply(sourceCoord, areaSize).add(StructureBoundingBoxes.min(context.boundingBox));
                if (context.includes(worldCoord))
                {
                    IBlockState state = context.environment.world.getBlockState(worldCoord);
                    if (!transformer.transformer.skipGeneration(transformer.instanceData, context, worldCoord, state, worldData, sourceCoord))
                        ruins.decayBlock(context.environment.world, context.random, state, worldCoord, relevantBB);
                }
            }
        }
    }

    private static class Fixture
    {
        final BlockPos origin = new BlockPos(-18, 60, -34);
        final BlockPos corner = origin.add(1, 1, 1);
        final List<String> trace = new ArrayList<>();
        final List<BlockPos> writes = new ArrayList<>();
        final Map<BlockPos, IBlockState> blocks = new HashMap<>();
        final IvWorldData data = new IvWorldData(new IvBlockCollection(3, 3, 3), Collections.emptyList(), Collections.emptyList());
        final TransformerRuins ruins;
        final TransformerRuins.InstanceData instance = new TransformerRuins.InstanceData();
        final RunTransformer run;
        AxisAlignedTransform2D transform = AxisAlignedTransform2D.R0;
        final Random random = new Random(12345) {
            @Override protected int next(int bits)
            {
                int value = super.next(bits);
                trace.add("random:" + bits + ":" + value);
                return value;
            }
        };
        final WorldServer world = mock(WorldServer.class, withSettings().stubOnly().defaultAnswer(invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("getBlockState"))
            {
                BlockPos pos = invocation.getArgument(0);
                trace.add("read:" + pos);
                return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
            }
            if (method.equals("setBlockState"))
            {
                BlockPos pos = ((BlockPos) invocation.getArgument(0)).toImmutable();
                IBlockState state = invocation.getArgument(1);
                trace.add("write:" + pos + ":" + state + ":" + invocation.getArgument(2));
                writes.add(pos);
                blocks.put(pos, state);
                return true;
            }
            throw new AssertionError("Unexpected world access (including chunk requests): " + method);
        }));

        Fixture(float erosion, float vines, float cobwebs, boolean supported)
        {
            ruins = new TransformerRuins("test", EnumFacing.DOWN, 0, 0, 0, 0, false, erosion, vines, cobwebs);
            TransformerMulti multi = mock(TransformerMulti.class);
            when(multi.skipGeneration(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                BlockPos pos = invocation.getArgument(2);
                trace.add("skip:" + pos);
                return ruins.skipGeneration(instance, invocation.getArgument(1), pos, invocation.getArgument(3), data, invocation.getArgument(5));
            });
            run = new RunTransformer(multi, new TransformerMulti.InstanceData());
            if (supported)
            {
                blocks.put(corner.down(), Blocks.STONEBRICK.getDefaultState());
                blocks.put(corner.west(), Blocks.STONE.getDefaultState());
                blocks.put(corner.north(), Blocks.STONE.getDefaultState());
            }
        }

        StructureBoundingBox clip(int minX, int maxX)
        {
            return new StructureBoundingBox(origin.add(minX, 0, 0), origin.add(maxX, 2, 2));
        }

        StructureSpawnContext context(StructureBoundingBox clip, boolean completion)
        {
            return new StructureSpawnContext(new Environment(world, null, null, null), random, transform,
                    new StructureBoundingBox(origin, origin.add(2, 2, 2)), clip, null, 0, false,
                    completion ? StructureSpawnContext.GenerateMaturity.COMPLEMENT : StructureSpawnContext.GenerateMaturity.FIRST);
        }

        void run(StructureBoundingBox clip, boolean completion)
        {
            ruins.transform(instance, Transformer.Phase.AFTER, context(clip, completion), data, run);
            assertTrue(RecurrentComplex.forgeEventHandler.disabledTileDropAreas.isEmpty());
        }
    }
}
