package ivorius.reccomplex.world.gen.feature.structure.generic.transformers;

import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.tools.IvWorldData;
import ivorius.reccomplex.utils.RCBlockLogic;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Ordered observations include mod-style foliage callbacks, attempted writes and clipped writes. */
public class NaturalAirBehaviorTest
{
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void rotationsMirrorsClipsAndUnsupportedPlantsMatchOriginal()
    {
        for (int rotation = 0; rotation < 4; rotation++) for (boolean mirror : new boolean[]{false, true})
            for (String scene : Arrays.asList("empty", "obstacle", "unsupported", "supported", "bush"))
                compare(scene, 12, AxisAlignedTransform2D.from(rotation, mirror), false);
    }

    @Test public void treeSizeBoundaryAndSavedCloudsMatchOriginal()
    {
        for (int size : new int[]{299, 300, 301})
            for (String scene : Arrays.asList("supported", "unsupported"))
                compare(scene, size, AxisAlignedTransform2D.R0, true);
    }

    private void compare(String scene, int size, AxisAlignedTransform2D transform, boolean restore)
    {
        Fixture expected = new Fixture(scene, size, transform);
        Fixture actual = new Fixture(scene, size, transform);
        TransformerNaturalAir.InstanceData cloud = new TransformerNaturalAir.InstanceData();
        cloud.cloud.put(BlockPos.ORIGIN, 1);
        cloud.cloud.put(new BlockPos(1, 0, 1), 1);
        if (restore)
        {
            TransformerNaturalAir.InstanceData restored = new TransformerNaturalAir.InstanceData();
            restored.readFromNBT(cloud.writeToNBT());
            cloud = restored;
        }
        TransformerNaturalAir transformer = mock(TransformerNaturalAir.class, CALLS_REAL_METHODS);
        for (int pass = 0; pass < 3; pass++)
        {
            StructureSpawnContext oldContext = expected.context(pass);
            StructureSpawnContext newContext = actual.context(pass);
            legacyAfter(cloud, oldContext, expected.data);
            transformer.transform(cloud, Transformer.Phase.AFTER, newContext, actual.data, null);
            assertEquals(scene + " size=" + size + " pass=" + pass, expected.trace, actual.trace);
            assertEquals(expected.blocks.keySet(), actual.blocks.keySet());
            for (BlockPos pos : expected.blocks.keySet())
                assertEquals(expected.kind(expected.blocks.get(pos)), actual.kind(actual.blocks.get(pos)));
            expected.trace.clear(); actual.trace.clear();
            verifyZeroInteractions(oldContext.random, newContext.random);
        }
    }

    // Frozen copy of the pre-optimization AFTER algorithm, including the original collection and traversal.
    static void legacyAfter(TransformerNaturalAir.InstanceData data, StructureSpawnContext context, IvWorldData worldData)
    {
        WorldServer world = context.environment.world;
        int[] size = {worldData.blockCollection.width, worldData.blockCollection.height, worldData.blockCollection.length};
        BlockPos origin = new BlockPos(context.boundingBox.minX, context.boundingBox.minY, context.boundingBox.minZ);
        HashSet<BlockPos> check = NaturalAirBenchmark.legacyCandidates(data.cloud, context.transform, size, origin);
        Set<BlockPos> remove = new HashSet<>();
        HashSet<BlockPos> start = new HashSet<>();
        check.forEach(checking -> {
            start.add(checking);
            if (TransformerAbstractCloud.visitRecursively(start, (changed, pos) -> {
                IBlockState state = world.getBlockState(pos);
                boolean foliage = RCBlockLogic.isFoliage(state, world, pos);
                if (!RCBlockLogic.canStay(state, world, pos)) context.setBlock(pos, Blocks.AIR.getDefaultState(), 2);
                else if (!foliage && !state.getBlock().isReplaceable(world, pos)) return false;
                else if (foliage && remove.size() < 300 && remove.add(pos))
                    TransformerNaturalAir.neighbors(pos).forEach(changed::add);
                return true;
            })) remove.forEach(pos -> context.setBlock(pos, Blocks.AIR.getDefaultState(), 2));
            start.clear(); remove.clear();
        });
    }

    static class Fixture
    {
        final List<String> trace = new ArrayList<>();
        final Map<BlockPos, IBlockState> blocks = new HashMap<>();
        final BlockPos origin = new BlockPos(-70, 88, -502);
        final AxisAlignedTransform2D transform;
        final IvWorldData data = new IvWorldData(new IvBlockCollection(3, 3, 3), Collections.emptyList(), Collections.emptyList());
        final IBlockState empty = block("air", false, true).getDefaultState();
        final IBlockState foliage = block("foliage", true, true).getDefaultState();
        final IBlockState solid = block("solid", false, false).getDefaultState();
        final IBlockState bush = new BlockBush(Material.PLANTS) {
            @Override public boolean isFoliage(IBlockAccess world, BlockPos pos) { record("foliage:bush", pos); return true; }
            @Override public boolean canPlaceBlockAt(World world, BlockPos pos) { record("canStay:bush", pos); return false; }
        }.getDefaultState();
        final WorldServer world = mock(WorldServer.class, withSettings().stubOnly().defaultAnswer(invocation -> {
            if (invocation.getMethod().getName().equals("getBlockState"))
            {
                BlockPos pos = invocation.getArgument(0);
                record("read", pos);
                IBlockState state = blocks.getOrDefault(pos, empty);
                return state == Blocks.AIR.getDefaultState() ? empty : state;
            }
            throw new AssertionError("Unexpected world access: " + invocation.getMethod().getName());
        }));

        Fixture(String scene, int size, AxisAlignedTransform2D transform)
        {
            this.transform = transform;
            if (scene.equals("obstacle")) blocks.put(origin.add(-1, 0, 0), solid);
            else if (scene.equals("bush")) blocks.put(origin.add(-1, 0, 0), bush);
            else if (!scene.equals("empty"))
            {
                for (int i = 0; i < size; i++) blocks.put(origin.add(-1, 0, i), foliage);
                if (scene.equals("supported")) blocks.put(origin.add(-1, -1, size / 2), solid);
            }
        }

        Block block(String name, boolean isFoliage, boolean replaceable)
        {
            return new Block(Material.ROCK) {
                @Override public boolean isFoliage(IBlockAccess world, BlockPos pos) { record("foliage:" + name, pos); return isFoliage; }
                @Override public boolean isWood(IBlockAccess world, BlockPos pos) { record("wood:" + name, pos); return false; }
                @Override public boolean isLeaves(IBlockState state, IBlockAccess world, BlockPos pos) { record("leaves:" + name, pos); return false; }
                @Override public boolean isReplaceable(IBlockAccess world, BlockPos pos) { record("replaceable:" + name, pos); return replaceable; }
            };
        }

        void record(String operation, BlockPos pos) { trace.add(operation + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ()); }
        String kind(IBlockState state) { return state == empty ? "empty" : state == foliage ? "foliage" : state == solid ? "solid" : state == bush ? "bush" : "written-air"; }

        StructureSpawnContext context(int pass)
        {
            StructureBoundingBox clip = pass == 0 ? new StructureBoundingBox(-72, 1, -504, -70, 254, -489)
                    : pass == 1 ? new StructureBoundingBox(-69, 1, -504, -54, 254, -489) : null;
            return new StructureSpawnContext(new Environment(world, null, null, null), mock(Random.class), transform,
                    new StructureBoundingBox(origin, origin.add(2, 2, 2)), clip, null, 0, false,
                    pass == 0 ? StructureSpawnContext.GenerateMaturity.FIRST : StructureSpawnContext.GenerateMaturity.COMPLEMENT) {
                @Override public boolean setBlock(BlockPos pos, IBlockState state, int flags) {
                    record("attempt:" + flags, pos);
                    if (!includes(pos)) return false;
                    record("write:" + flags, pos);
                    blocks.put(pos.toImmutable(), state);
                    return true;
                }
            };
        }
    }
}
