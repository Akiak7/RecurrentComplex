package ivorius.reccomplex.world;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.ivtoolkit.util.IvStreams;
import net.minecraft.util.math.ChunkPos;

import java.util.stream.IntStream;
import java.util.stream.Stream;

// TODO Temp drop-in for an unreleased change to the IvToolkit API.
public class RCChunks {
    public static IntStream repeatsInChunk(int chunkPos, int shift, int repeatLength) {
        int chunkMin = chunkPos << 4;
        int chunkMax = chunkMin + 15;

        if (repeatLength <= 0) {
            return shift >= chunkMin && shift <= chunkMax
                ? IntStream.of(shift)
                : IntStream.empty();
        }

        int first = shift + ceilDiv(chunkMin - shift, repeatLength) * repeatLength;
        int last = shift + Math.floorDiv(chunkMax - shift, repeatLength) * repeatLength;

        if (first > last || first > chunkMax) {
            return IntStream.empty();
        }

        int count = ((last - first) / repeatLength) + 1;
        return IntStream.iterate(first, x -> x + repeatLength).limit(count);
    }

    public static Stream<BlockSurfacePos> repeatIntersections(ChunkPos chunkPos, BlockSurfacePos pos, int repeatX, int repeatZ) {
        IntStream xStream = repeatsInChunk(chunkPos.x, pos.x, repeatX);

        return IvStreams.flatMapToObj(xStream, x ->
            repeatsInChunk(chunkPos.z, pos.z, repeatZ).mapToObj(z -> new BlockSurfacePos(x, z))
        );
    }

    private static int ceilDiv(int numerator, int denominator) {
        return -Math.floorDiv(-numerator, denominator);
    }
}
