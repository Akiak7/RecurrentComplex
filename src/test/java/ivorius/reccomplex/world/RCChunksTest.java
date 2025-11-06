package ivorius.reccomplex.world;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import net.minecraft.util.math.ChunkPos;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RCChunksTest {
    @Test
    public void repeatsInChunkRespectsBounds() {
        int[] positions = RCChunks.repeatsInChunk(8, 0, 128).toArray();
        assertArrayEquals(new int[]{128}, positions);
    }

    @Test
    public void repeatsInChunkBacktracksIntoChunk() {
        int[] positions = RCChunks.repeatsInChunk(0, 64, 32).toArray();
        assertArrayEquals(new int[]{0}, positions);
    }

    @Test
    public void repeatIntersectionsProducesCartesianProduct() {
        List<BlockSurfacePos> positions = RCChunks
                .repeatIntersections(new ChunkPos(0, 0), new BlockSurfacePos(0, 0), 8, 8)
                .collect(Collectors.toList());

        assertEquals(4, positions.size());
        assertEquals(new BlockSurfacePos(0, 0), positions.get(0));
        assertEquals(new BlockSurfacePos(0, 8), positions.get(1));
        assertEquals(new BlockSurfacePos(8, 0), positions.get(2));
        assertEquals(new BlockSurfacePos(8, 8), positions.get(3));
    }

    @Test
    public void zeroOrNegativeRepeatFallsBackToShift() {
        int[] positions = RCChunks.repeatsInChunk(0, 8, 0).toArray();
        assertArrayEquals(new int[]{8}, positions);
    }
}
