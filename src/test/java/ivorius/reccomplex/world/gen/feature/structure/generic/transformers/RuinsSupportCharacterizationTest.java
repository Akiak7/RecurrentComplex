package ivorius.reccomplex.world.gen.feature.structure.generic.transformers;

import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.tools.IvWorldData;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.events.handlers.RCForgeEventHandler;
import ivorius.reccomplex.utils.accessor.RCAccessorWorldServer;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.context.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericStructure;
import ivorius.reccomplex.world.gen.feature.structure.registry.MCRegistrySpecial;
import net.minecraft.block.Block;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockWallSign;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.junit.*;

import java.util.*;
import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Characterizes current behaviour, not a proposed support-cleanup implementation. */
public class RuinsSupportCharacterizationTest
{
    private static final BlockPos SUPPORT = new BlockPos(1, 2, 2);
    private static final BlockPos DECORATION = new BlockPos(2, 2, 2);
    private static final BlockPos ALTERNATIVE = new BlockPos(2, 2, 1);
    private RCForgeEventHandler previousHandler;
    private MCRegistrySpecial previousRegistry;
    private final Map<Field, Object> accessorCache = new LinkedHashMap<>();

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }
    @Before public void installGlobals() throws Exception
    {
        previousHandler = RecurrentComplex.forgeEventHandler;
        previousRegistry = RecurrentComplex.specialRegistry;
        RecurrentComplex.forgeEventHandler = new RCForgeEventHandler();
        RecurrentComplex.specialRegistry = mock(MCRegistrySpecial.class);
        when(RecurrentComplex.specialRegistry.isSafe(any(Block.class))).thenReturn(true);
        // Forge's obfuscation detection is not bootstrapped in plain JUnit. Seed only
        // the cached field lookup with the mapped field, then restore all accessor caches.
        for (Field field : RCAccessorWorldServer.class.getDeclaredFields())
        {
            field.setAccessible(true);
            accessorCache.put(field, field.get(null));
            if (field.getName().equals("blockEventQueueField"))
            {
                Field mapped = WorldServer.class.getDeclaredField("blockEventQueue");
                mapped.setAccessible(true);
                field.set(null, mapped);
            }
        }
    }
    @After public void restoreGlobals() throws Exception
    {
        RecurrentComplex.forgeEventHandler = previousHandler;
        RecurrentComplex.specialRegistry = previousRegistry;
        for (Map.Entry<Field, Object> entry : accessorCache.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test public void findAndRecordRealPreparedDecayCases()
    {
        for (boolean torch : new boolean[]{false, true})
        {
            long seed = findSeed(torch);
            assertEquals(torch ? 409 : 289, seed);
            Fixture f = new Fixture(torch, true, true, true, AxisAlignedTransform2D.R0);
            f.prepare(seed, false);
            assertTrue(f.skips(SUPPORT));
            assertFalse(f.skips(DECORATION));
            assertFalse(f.skips(ALTERNATIVE));
            System.out.println("CASE " + (torch ? "torch" : "wall_sign") + " seed=" + seed
                    + " direction=DOWN min=max=0.15 chaos=0.6 density=0.1 gravity=false erosion=vines=cobwebs=0");
            System.out.println("PREPARED " + f.instance.writeToNBT());
            System.out.println("DECAY support=" + f.ruinsData().getDecay(SUPPORT) + " decoration=" + f.ruinsData().getDecay(DECORATION)
                    + " alternative=" + f.ruinsData().getDecay(ALTERNATIVE));
            f.generate(0);
            System.out.println("TRACE " + f.trace);
            assertEquals(torch ? Blocks.AIR : Blocks.WALL_SIGN, f.state(f.worldPos(DECORATION)).getBlock());
            assertEquals(Blocks.AIR, f.state(f.worldPos(SUPPORT)).getBlock());
            assertEquals(Blocks.PLANKS, f.state(f.worldPos(ALTERNATIVE)).getBlock());
        }
    }

    @Test public void removedSupportAcrossTransformsChunkOrdersAndSavedData()
    {
        for (boolean torch : new boolean[]{false, true})
        {
            long seed = torch ? 409 : 289;
            for (int rotation = 0; rotation < 4; rotation++) for (boolean mirror : new boolean[]{false, true})
                for (int order = 0; order < 3; order++) for (boolean restore : new boolean[]{false, true})
                {
                    Fixture f = new Fixture(torch, true, false, true, AxisAlignedTransform2D.from(rotation, mirror));
                    f.prepare(seed, restore);
                    assertTrue(f.skips(SUPPORT));
                    assertFalse(f.skips(DECORATION));
                    f.generate(order);
                    assertEquals(f.label(order, restore), Blocks.AIR, f.state(f.worldPos(SUPPORT)).getBlock());
                    assertEquals(f.label(order, restore), torch ? Blocks.AIR : Blocks.WALL_SIGN, f.state(f.worldPos(DECORATION)).getBlock());
                }
        }
    }

    @Test public void intactSupportShowsTorchCompletionOrderSensitivity()
    {
        for (boolean torch : new boolean[]{false, true})
            for (int rotation = 0; rotation < 4; rotation++) for (boolean mirror : new boolean[]{false, true})
                for (int order = 0; order < 3; order++) for (boolean restore : new boolean[]{false, true})
                {
                    Fixture f = new Fixture(torch, true, false, false, AxisAlignedTransform2D.from(rotation, mirror));
                    f.prepare(0, restore);
                    assertFalse(f.skips(SUPPORT));
                    assertFalse(f.skips(DECORATION));
                    f.generate(order);
                    assertEquals(Blocks.PLANKS, f.state(f.worldPos(SUPPORT)).getBlock());
                    assertEquals(f.label(order, restore), torch && order == 2 ? Blocks.AIR : f.decoration.getBlock(), f.state(f.worldPos(DECORATION)).getBlock());
                    if (rotation == 0 && !mirror && !restore)
                        System.out.println("INTACT " + f.label(order, restore));
                }
    }

    @Test public void omittedSourceSupportCanStillExistInTheWorld()
    {
        for (boolean torch : new boolean[]{false, true})
        {
            Fixture f = new Fixture(torch, true, false, true, AxisAlignedTransform2D.R0);
            f.prepare(torch ? 409 : 289, true);
            assertTrue(f.skips(SUPPORT));
            f.blocks.put(f.worldPos(SUPPORT), Blocks.PLANKS.getDefaultState());
            f.generate(0);
            assertEquals(Blocks.PLANKS, f.state(f.worldPos(SUPPORT)).getBlock());
            assertEquals(f.decoration.getBlock(), f.state(f.worldPos(DECORATION)).getBlock());
        }
    }

    @Test public void alreadyUnsupportedAndAlternativeWallDoNotProveRuinsDamage()
    {
        for (boolean torch : new boolean[]{false, true}) for (boolean alternative : new boolean[]{false, true})
        {
            Fixture f = new Fixture(torch, false, alternative, false, AxisAlignedTransform2D.R0);
            f.prepare(0, true);
            f.generate(0);
            assertEquals(torch ? Blocks.AIR : Blocks.WALL_SIGN, f.state(f.worldPos(DECORATION)).getBlock());
            if (alternative) assertEquals(Blocks.PLANKS, f.state(f.worldPos(ALTERNATIVE)).getBlock());
        }
    }

    @Test public void realWallSignNeighbourCallbackDistinguishesActualAttachment()
    {
        for (boolean support : new boolean[]{false, true})
        {
            Fixture f = new Fixture(false, support, true, false, AxisAlignedTransform2D.R0);
            f.prepare(0, false);
            f.generate(0);
            BlockPos pos = f.worldPos(DECORATION);
            IBlockState state = f.state(pos);
            state.neighborChanged(f.world, pos, Blocks.PLANKS, f.worldPos(ALTERNATIVE));
            assertEquals(support ? Blocks.WALL_SIGN : Blocks.AIR, f.state(pos).getBlock());
        }
    }

    private static long findSeed(boolean torch)
    {
        Fixture f = new Fixture(torch, true, true, true, AxisAlignedTransform2D.R0);
        for (long seed = 0; seed < 4096; seed++)
        {
            f.prepare(seed, false);
            if (f.ruinsData().getDecay(DECORATION) > 0 && f.ruinsData().getDecay(ALTERNATIVE) > 0
                    && f.skips(SUPPORT) && !f.skips(DECORATION) && !f.skips(ALTERNATIVE))
                return seed;
        }
        throw new AssertionError("No prepared case found for torch=" + torch);
    }

    private static class Fixture
    {
        final List<String> trace = new ArrayList<>();
        final Map<BlockPos, IBlockState> blocks = new HashMap<>();
        final int[] size = {5, 5, 5};
        final AxisAlignedTransform2D transform;
        final BlockPos origin;
        final StructureBoundingBox bounds;
        final IBlockState decoration;
        final IvWorldData data;
        final TransformerRuins ruins;
        final GenericStructure structure;
        final TransformerMulti foreign = new TransformerMulti("foreign", "");
        GenericStructure.InstanceData instance;
        boolean restoreBetweenPasses;
        final WorldServer world = mock(WorldServer.class, withSettings().stubOnly().defaultAnswer(invocation -> {
            String method = invocation.getMethod().getName();
            BlockPos pos;
            switch (method)
            {
                case "getBlockState":
                    pos = invocation.getArgument(0);
                    trace.add("read " + pos + " = " + state(pos));
                    return state(pos);
                case "setBlockState":
                    return write(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                case "setBlockToAir":
                    return write(invocation.getArgument(0), Blocks.AIR.getDefaultState(), 3);
                default:
                    throw new AssertionError("Unexpected world method/chunk request: " + method);
            }
        }));
        final Environment environment = new Environment(world, null, null, null);

        Fixture(boolean torch, boolean support, boolean alternative, boolean decay, AxisAlignedTransform2D transform)
        {
            this.transform = transform;
            // Put these adjacent positions on opposite sides of the -16 chunk boundary,
            // regardless of rotation or mirror. The other horizontal axis stays within a chunk.
            BlockPos a = transform.apply(SUPPORT, size), b = transform.apply(DECORATION, size);
            origin = new BlockPos(a.getX() != b.getX() ? -16 - Math.max(a.getX(), b.getX()) : -24,
                    60, a.getZ() != b.getZ() ? -16 - Math.max(a.getZ(), b.getZ()) : -24);
            bounds = new StructureBoundingBox(origin, origin.add(4, 4, 4));
            decoration = torch ? Blocks.TORCH.getDefaultState().withProperty(BlockTorch.FACING, EnumFacing.EAST)
                    : Blocks.WALL_SIGN.getDefaultState().withProperty(BlockWallSign.FACING, EnumFacing.EAST);
            IvBlockCollection collection = new IvBlockCollection(5, 5, 5);
            for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++)
                collection.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
            collection.setBlockState(DECORATION, decoration);
            if (support) collection.setBlockState(SUPPORT, Blocks.PLANKS.getDefaultState());
            if (alternative) collection.setBlockState(ALTERNATIVE, Blocks.PLANKS.getDefaultState());
            data = new IvWorldData(collection, Collections.emptyList(), Collections.emptyList());
            ruins = new TransformerRuins("ruins", EnumFacing.DOWN, decay ? 0.15f : 0, decay ? 0.15f : 0,
                    0.6f, 0.1f, false, 0, 0, 0);
            structure = new GenericStructure() {
                @Override public IvWorldData constructWorldData() { return data; }
            };
            structure.transformer = new TransformerMulti("ruins-group", "", Collections.singletonList(ruins));
            // Suppress item spawning/harvest hooks only. This is distinct from captureBlockSnapshots;
            // normal onBlockAdded support callbacks remain enabled and run below.
            world.restoringBlockSnapshots = true;
        }

        void prepare(long seed, boolean restore)
        {
            restoreBetweenPasses = restore;
            instance = structure.prepareInstanceData(new StructurePrepareContext(transform, bounds, false, environment,
                    new Random(seed), StructureSpawnContext.GenerateMaturity.FIRST), foreign);
            if (restore) restore();
        }

        void restore()
        {
            String before = instance.writeToNBT().toString();
            instance = structure.loadInstanceData(new StructureLoadContext(transform, bounds, false), instance.writeToNBT(), foreign);
            assertEquals(before, instance.writeToNBT().toString());
        }

        TransformerRuins.InstanceData ruinsData()
        {
            return (TransformerRuins.InstanceData) instance.transformerData.pairedTransformers.get(0).getRight();
        }

        boolean skips(BlockPos source)
        {
            return ruins.skipGeneration(ruinsData(), context(null, false), worldPos(source),
                    data.blockCollection.getBlockState(source), data, source);
        }

        BlockPos worldPos(BlockPos source) { return transform.apply(source, size).add(origin); }
        IBlockState state(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState()); }

        boolean write(BlockPos position, IBlockState state, int flags)
        {
            BlockPos pos = position.toImmutable();
            IBlockState previous = state(pos);
            trace.add("write " + pos + " = " + state + " flags=" + flags);
            if (previous == state) return false;
            blocks.put(pos, state);
            if (previous.getBlock() != state.getBlock())
            {
                trace.add("onBlockAdded " + pos + " = " + state);
                state.getBlock().onBlockAdded(world, pos, state);
            }
            if ((flags & 1) != 0)
                for (EnumFacing facing : EnumFacing.values())
                {
                    BlockPos neighbour = pos.offset(facing);
                    trace.add("neighbour " + neighbour + " from=" + pos);
                    state(neighbour).neighborChanged(world, neighbour, previous.getBlock(), pos);
                }
            return true;
        }

        StructureSpawnContext context(StructureBoundingBox clip, boolean completion)
        {
            return new StructureSpawnContext(environment, new Random(99), transform, bounds, clip, null, 0, false,
                    completion ? StructureSpawnContext.GenerateMaturity.COMPLEMENT : StructureSpawnContext.GenerateMaturity.FIRST) {
                // No real chunks or lighting in this harness. Keep real generation, setBlock,
                // transformer callbacks, attachment checks and block-state transforms.
                @Override public void freezeHeightMap(StructureBoundingBox area) { }
                @Override public void meltHeightMap() { }
            };
        }

        StructureBoundingBox chunk(BlockPos pos)
        {
            int x = (pos.getX() >> 4) << 4, z = (pos.getZ() >> 4) << 4;
            return new StructureBoundingBox(x, 0, z, x + 15, 255, z + 15);
        }

        void generate(int order)
        {
            trace.add("BEFORE support=" + state(worldPos(SUPPORT)) + " decoration=" + state(worldPos(DECORATION)));
            if (order == 0) structure.generate(context(null, false), instance, foreign);
            else
            {
                BlockPos first = worldPos(order == 1 ? SUPPORT : DECORATION);
                BlockPos second = worldPos(order == 1 ? DECORATION : SUPPORT);
                assertFalse(chunk(first).isVecInside(second));
                structure.generate(context(chunk(first), false), instance, foreign);
                trace.add("AFTER INITIAL support=" + state(worldPos(SUPPORT)) + " decoration=" + state(worldPos(DECORATION)));
                if (order == 2)
                {
                    assertEquals(Blocks.AIR, state(worldPos(SUPPORT)).getBlock());
                    assertEquals(decoration.getBlock() == Blocks.TORCH ? Blocks.AIR : Blocks.WALL_SIGN, state(worldPos(DECORATION)).getBlock());
                }
                if (restoreBetweenPasses) restore();
                structure.generate(context(chunk(second), true), instance, foreign);
            }
            trace.add("FINAL support=" + state(worldPos(SUPPORT)) + " decoration=" + state(worldPos(DECORATION)));
            assertTrue(RecurrentComplex.forgeEventHandler.disabledTileDropAreas.isEmpty());
        }

        String label(int order, boolean restore) { return decoration + " transform=" + transform + " order=" + order + " restored=" + restore + " trace=" + trace; }
    }
}
