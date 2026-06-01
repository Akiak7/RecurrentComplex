/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.world.gen.feature;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.ivtoolkit.world.chunk.Chunks;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.utils.RCStructureBoundingBoxes;
import ivorius.reccomplex.world.RCChunks;
import ivorius.reccomplex.world.gen.feature.selector.MixingStructureSelector;
import ivorius.reccomplex.world.gen.feature.selector.NaturalStructureSelector;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.NaturalGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.StaticGeneration;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

public class StructureLocator
{
    public static final int DEFAULT_RADIUS = 128;
    public static final int CHUNK_POPULATION_SALT = 0xDEADBEEF;

    @Nonnull
    public static Optional<Result> locate(WorldServer world, String structureID, BlockPos origin, int radius, boolean uncheckedOnly)
    {
        Structure<?> structure = StructureRegistry.INSTANCE.get(structureID);
        if (structure == null)
            return Optional.empty();

        if (RCConfig.honorStructureGenerationOption && !world.getWorldInfo().isMapFeaturesEnabled())
            return Optional.empty();

        WorldStructureGenerationData generationData = WorldStructureGenerationData.get(world);
        Result best = null;

        for (ChunkPos chunkPos : chunksByDistance(origin, radius))
        {
            if (uncheckedOnly && generationData.isChunkChecked(chunkPos))
                continue;

            Result result = locateInChunk(world, structureID, structure, origin, chunkPos);
            if (result != null && (best == null || result.distanceSq < best.distanceSq))
                best = result;
        }

        return Optional.ofNullable(best);
    }

    @Nullable
    protected static Result locateInChunk(WorldServer world, String structureID, Structure<?> structure, BlockPos origin, ChunkPos chunkPos)
    {
        Random random = chunkRandom(world, chunkPos);
        Result best = null;

        for (Seeded<StaticCandidate> candidate : seedCandidates(staticCandidatesInChunk(world, chunkPos), random))
        {
            if (!structureID.equals(candidate.item.structureID))
                continue;

            Result result = testStatic(world, origin, chunkPos, candidate.item, candidate.seed).orElse(null);
            if (result != null && (best == null || result.distanceSq < best.distanceSq))
                best = result;
        }

        if (mayGenerateNaturally(world, chunkPos))
        {
            List<Pair<Structure<?>, NaturalGeneration>> generated = naturalCandidatesInChunk(world, chunkPos, random);
            for (Seeded<Pair<Structure<?>, NaturalGeneration>> candidate : seedCandidates(generated, random))
            {
                if (candidate.item == null || candidate.item.getLeft() != structure)
                    continue;

                Result result = testNatural(world, origin, chunkPos, structureID, structure, candidate.item.getRight(), candidate.seed).orElse(null);
                if (result != null && (best == null || result.distanceSq < best.distanceSq))
                    best = result;
            }
        }

        return best;
    }

    @Nonnull
    protected static List<StaticCandidate> staticCandidatesInChunk(WorldServer world, ChunkPos chunkPos)
    {
        List<StaticCandidate> candidates = new ArrayList<>();
        BlockPos spawnPos = world.getSpawnPoint();

        for (Pair<Structure<?>, StaticGeneration> pair : StructureRegistry.INSTANCE.getGenerationTypes(StaticGeneration.class))
        {
            Structure<?> structure = pair.getLeft();
            StaticGeneration generation = pair.getRight();

            if (!generation.dimensionExpression.test(world.provider))
                continue;

            String structureID = StructureRegistry.INSTANCE.id(structure);
            if (structureID == null)
                continue;

            staticPositionsInChunk(generation, chunkPos, spawnPos)
                    .forEach(pos -> candidates.add(new StaticCandidate(structureID, structure, generation, pos)));
        }

        return candidates;
    }

    @Nonnull
    public static Stream<BlockSurfacePos> staticPositionsInChunk(StaticGeneration generation, ChunkPos chunkPos, BlockPos spawnPos)
    {
        return staticPositionsInChunk(generation.getPos(spawnPos), generation.pattern, chunkPos);
    }

    @Nonnull
    public static Stream<BlockSurfacePos> staticPositionsInChunk(BlockSurfacePos pos, @Nullable StaticGeneration.Pattern pattern, ChunkPos chunkPos)
    {
        return pattern != null
                ? RCChunks.repeatIntersections(chunkPos, pos, pattern.repeatX, pattern.repeatZ)
                : Chunks.contains(chunkPos, pos) ? Stream.of(pos) : Stream.empty();
    }

    @Nonnull
    protected static List<Pair<Structure<?>, NaturalGeneration>> naturalCandidatesInChunk(WorldServer world, ChunkPos chunkPos, Random random)
    {
        Biome selectorBiome = world.getBiome(chunkPos.getBlock(8, 0, 8));
        MixingStructureSelector<NaturalGeneration, NaturalStructureSelector.Category> selector =
                NaturalGeneration.selectors(StructureRegistry.INSTANCE).get(selectorBiome, world.provider);

        float distanceToSpawn = chunkDistance(new ChunkPos(world.getSpawnPoint()), chunkPos);
        return selector.generatedStructures(random, world.getBiome(chunkPos.getBlock(0, 0, 0)), world.provider, distanceToSpawn);
    }

    protected static boolean mayGenerateNaturally(WorldServer world, ChunkPos chunkPos)
    {
        Biome biome = world.getBiome(chunkPos.getBlock(8, 0, 8));
        boolean mayGenerate = RCConfig.isGenerationEnabled(biome) && RCConfig.isGenerationEnabled(world.provider);

        if (world.provider.getDimension() == 0)
        {
            BlockPos spawnPos = world.getSpawnPoint();
            double dx = chunkPos.x * 16 + 8 - spawnPos.getX();
            double dz = chunkPos.z * 16 + 8 - spawnPos.getZ();
            mayGenerate &= dx * dx + dz * dz >= RCConfig.minDistToSpawnForGeneration * RCConfig.minDistToSpawnForGeneration;
        }

        return mayGenerate;
    }

    @Nonnull
    protected static Optional<Result> testStatic(WorldServer world, BlockPos origin, ChunkPos chunkPos, StaticCandidate candidate, long seed)
    {
        StructureGenerator<?> generator = new StructureGenerator<>(candidate.structure).world(world).generationInfo(candidate.generation)
                .seed(seed).structureID(candidate.structureID).maturity(StructureSpawnContext.GenerateMaturity.SUGGEST)
                .randomPosition(candidate.position, candidate.generation.placer()).fromCenter(true)
                .partially(RecurrentComplex.PARTIALLY_SPAWN_NATURAL_STRUCTURES, chunkPos);

        return testGenerator(generator, origin, chunkPos, candidate.structureID, candidate.generation.id(), "static", seed);
    }

    @Nonnull
    protected static Optional<Result> testNatural(WorldServer world, BlockPos origin, ChunkPos chunkPos, String structureID, Structure<?> structure, NaturalGeneration generation, long seed)
    {
        if (generation.hasLimitations() && !generation.getLimitations().areResolved(world, structureID))
            return Optional.empty();

        StructureGenerator<?> generator = new StructureGenerator<>(structure).world(world).generationInfo(generation)
                .seed(seed).structureID(structureID).maturity(StructureSpawnContext.GenerateMaturity.SUGGEST)
                .randomPosition(WorldGenStructures.randomSurfacePos(chunkPos, seed), generation.placer()).fromCenter(true)
                .partially(RecurrentComplex.PARTIALLY_SPAWN_NATURAL_STRUCTURES, chunkPos);

        if (generation.getGenerationWeight(world.provider, generator.environment().biome) <= 0)
            return Optional.empty();

        return testGenerator(generator, origin, chunkPos, structureID, generation.id(), "natural", seed);
    }

    @Nonnull
    protected static Optional<Result> testGenerator(StructureGenerator<?> generator, BlockPos origin, ChunkPos chunkPos, String structureID, String generationID, String generationType, long seed)
    {
        try
        {
            StructureGenerator.GenerationResult result = generator.test();
            if (!result.succeeded())
                return Optional.empty();

            Optional<StructureBoundingBox> boundingBox = generator.boundingBox();
            if (!boundingBox.isPresent())
                return Optional.empty();

            return Optional.of(new Result(structureID, generationID, generationType, seed, boundingBox.get(), chunkPos, origin, generator.world().provider.getDimension()));
        }
        catch (Exception ignored)
        {
            return Optional.empty();
        }
    }

    @Nonnull
    public static <T> List<Seeded<T>> seedCandidates(Collection<T> candidates, Random random)
    {
        List<Seeded<T>> seeded = new ArrayList<>(candidates.size());
        for (T candidate : candidates)
            seeded.add(new Seeded<>(candidate, random.nextLong()));
        return seeded;
    }

    @Nonnull
    public static Random chunkRandom(WorldServer world, ChunkPos chunkPos)
    {
        long seed = (long) chunkPos.x * 341873128712L + (long) chunkPos.z * 132897987541L + world.getSeed() + CHUNK_POPULATION_SALT;
        return new Random(seed);
    }

    @Nonnull
    public static List<ChunkPos> chunksByDistance(BlockPos origin, int radius)
    {
        ChunkPos center = new ChunkPos(origin);
        int safeRadius = Math.max(0, radius);

        List<ChunkPos> chunks = new ArrayList<>((safeRadius * 2 + 1) * (safeRadius * 2 + 1));
        for (int x = center.x - safeRadius; x <= center.x + safeRadius; x++)
            for (int z = center.z - safeRadius; z <= center.z + safeRadius; z++)
                chunks.add(new ChunkPos(x, z));

        chunks.sort(Comparator
                .comparingLong((ChunkPos pos) -> chunkDistanceSq(origin, pos))
                .thenComparingInt(pos -> pos.x)
                .thenComparingInt(pos -> pos.z));
        return chunks;
    }

    protected static long chunkDistanceSq(BlockPos origin, ChunkPos chunkPos)
    {
        long minX = chunkPos.x * 16L;
        long minZ = chunkPos.z * 16L;
        long maxX = minX + 15L;
        long maxZ = minZ + 15L;
        long dx = origin.getX() < minX ? minX - origin.getX() : origin.getX() > maxX ? origin.getX() - maxX : 0;
        long dz = origin.getZ() < minZ ? minZ - origin.getZ() : origin.getZ() > maxZ ? origin.getZ() - maxZ : 0;
        return dx * dx + dz * dz;
    }

    protected static float chunkDistance(ChunkPos left, ChunkPos right)
    {
        return (float) Math.sqrt(
                (left.x - right.x) * (left.x - right.x) +
                        (left.z - right.z) * (left.z - right.z));
    }

    protected static long blockDistanceSq(BlockPos left, BlockPos right)
    {
        long dx = left.getX() - right.getX();
        long dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    protected static class StaticCandidate
    {
        public final String structureID;
        public final Structure<?> structure;
        public final StaticGeneration generation;
        public final BlockSurfacePos position;

        protected StaticCandidate(String structureID, Structure<?> structure, StaticGeneration generation, BlockSurfacePos position)
        {
            this.structureID = structureID;
            this.structure = structure;
            this.generation = generation;
            this.position = position;
        }
    }

    public static class Seeded<T>
    {
        public final T item;
        public final long seed;

        public Seeded(T item, long seed)
        {
            this.item = item;
            this.seed = seed;
        }
    }

    public static class Result
    {
        public final String structureID;
        public final String generationID;
        public final String generationType;
        public final long seed;
        public final StructureBoundingBox boundingBox;
        public final ChunkPos chunkPos;
        public final BlockPos position;
        public final int dimension;
        public final long distanceSq;

        public Result(String structureID, String generationID, String generationType, long seed, StructureBoundingBox boundingBox, ChunkPos chunkPos, BlockPos origin, int dimension)
        {
            this.structureID = structureID;
            this.generationID = generationID;
            this.generationType = generationType;
            this.seed = seed;
            this.boundingBox = boundingBox;
            this.chunkPos = chunkPos;
            this.position = RCStructureBoundingBoxes.getCenter(boundingBox);
            this.dimension = dimension;
            this.distanceSq = blockDistanceSq(origin, position);
        }

        public double distance()
        {
            return Math.sqrt(distanceSq);
        }
    }
}
