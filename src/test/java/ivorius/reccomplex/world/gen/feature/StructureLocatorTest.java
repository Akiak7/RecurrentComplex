package ivorius.reccomplex.world.gen.feature;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.StaticGeneration;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StructureLocatorTest
{
    @Test
    public void searchesOnlyOriginChunkAtRadiusZero()
    {
        List<ChunkPos> chunks = StructureLocator.chunksByDistance(new BlockPos(20, 64, -2), 0);

        assertEquals(1, chunks.size());
        assertEquals(new ChunkPos(1, -1), chunks.get(0));
    }

    @Test
    public void ordersOriginChunkFirst()
    {
        List<ChunkPos> chunks = StructureLocator.chunksByDistance(new BlockPos(20, 64, -2), 1);

        assertEquals(9, chunks.size());
        assertEquals(new ChunkPos(1, -1), chunks.get(0));
    }

    @Test
    public void consumesSeedForEveryCandidateBeforeFiltering()
    {
        List<String> candidates = Arrays.asList("first", "target", "third");
        Random expected = new Random(42);
        long firstSeed = expected.nextLong();
        long targetSeed = expected.nextLong();
        long thirdSeed = expected.nextLong();

        Random actual = new Random(42);
        List<StructureLocator.Seeded<String>> seeded = StructureLocator.seedCandidates(candidates, actual);

        assertEquals(firstSeed, seeded.get(0).seed);
        assertEquals(targetSeed, seeded.stream().filter(s -> s.item.equals("target")).findFirst().get().seed);
        assertEquals(thirdSeed, seeded.get(2).seed);
        assertEquals(expected.nextInt(), actual.nextInt());
    }

    @Test
    public void findsRepeatingStaticPositionsInIntersectingChunks()
    {
        StaticGeneration.Pattern pattern = new StaticGeneration.Pattern();
        pattern.repeatX = 32;
        pattern.repeatZ = 32;

        List<BlockSurfacePos> positions = StructureLocator.staticPositionsInChunk(new BlockSurfacePos(0, 0), pattern, new ChunkPos(2, 0))
                .collect(Collectors.toList());

        assertEquals(1, positions.size());
        assertTrue(positions.contains(new BlockSurfacePos(32, 0)));
    }
}
