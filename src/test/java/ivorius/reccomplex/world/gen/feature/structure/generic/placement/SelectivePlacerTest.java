package ivorius.reccomplex.world.gen.feature.structure.generic.placement;

import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SelectivePlacerTest
{
    private boolean previousAvoidChunks;

    @BeforeClass
    public static void bootstrapMinecraft()
    {
        Bootstrap.register();
    }

    @Before
    public void saveConfig()
    {
        previousAvoidChunks = RCConfig.avoidPlacerChunkGeneration;
    }

    @After
    public void restoreConfig()
    {
        RCConfig.avoidPlacerChunkGeneration = previousAvoidChunks;
    }

    @Test
    public void filtersUsingDestinationChunksForEveryRotationAndMirror()
    {
        RCConfig.avoidPlacerChunkGeneration = true;
        for (int rotation = 0; rotation < 4; rotation++)
        {
            for (boolean mirror : new boolean[]{false, true})
            {
                WorldServer world = mock(WorldServer.class);
                when(world.isChunkGeneratedAt(10, -10)).thenReturn(true);
                // The 32-block footprint straddles two world chunks, far from its local origin.
                boolean firstHalf = (rotation < 2) != mirror;
                Set<BlockPos> expected = footprint(firstHalf ? 0 : 16, firstHalf ? 16 : 32);
                assertSurface(world, AxisAlignedTransform2D.from(rotation, mirror), expected);
                verify(world, never()).isChunkGeneratedAt(0, 0);
                verify(world, never()).isChunkGeneratedAt(1, 0);
            }
        }
    }

    @Test
    public void disabledOptionKeepsFullFootprintWithoutCheckingChunks()
    {
        RCConfig.avoidPlacerChunkGeneration = false;
        WorldServer world = mock(WorldServer.class);
        assertSurface(world, AxisAlignedTransform2D.R1_F, footprint(0, 32));
        verifyZeroInteractions(world);
    }

    @Test
    public void noAvailableDestinationChunksKeepsExistingFallback()
    {
        RCConfig.avoidPlacerChunkGeneration = true;
        assertSurface(mock(WorldServer.class), AxisAlignedTransform2D.R0, footprint(0, 32));
    }

    private void assertSurface(WorldServer world, AxisAlignedTransform2D transform, Set<BlockPos> expected)
    {
        IvBlockCollection blocks = new IvBlockCollection(32, 1, 1);
        Random random = mock(Random.class);
        StructureBoundingBox bounds = transform.resultSwitchesXZ()
                ? new StructureBoundingBox(160, 0, -160, 160, 0, -129)
                : new StructureBoundingBox(160, 0, -160, 191, 0, -160);
        StructurePlaceContext context = new StructurePlaceContext(random,
                new Environment(world, null, null, null), transform, bounds);
        GenericPlacer downstream = mock(GenericPlacer.class);
        when(downstream.place(same(context), same(blocks), anySet())).thenAnswer(call -> {
            assertEquals(expected, call.getArgument(2));
            return 64;
        });

        assertEquals(64, new SelectivePlacer(downstream, 0).place(context, blocks));
        verify(downstream).place(same(context), same(blocks), eq(expected));
        verify(world, never()).getBlockState(any(BlockPos.class));
        verify(world, never()).getChunkFromChunkCoords(anyInt(), anyInt());
        verifyZeroInteractions(random);
    }

    private Set<BlockPos> footprint(int start, int end)
    {
        return IntStream.range(start, end).mapToObj(x -> new BlockPos(x, 0, 0)).collect(Collectors.toSet());
    }
}
