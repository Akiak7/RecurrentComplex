/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.world.gen.feature;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.ivtoolkit.math.IvVecMathHelper;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.world.gen.feature.selector.MixingStructureSelector;
import ivorius.reccomplex.world.gen.feature.selector.NaturalStructureSelector;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.Structures;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.NaturalGeneration;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.StaticGeneration;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by lukas on 24.05.14.
 */
public class WorldGenStructures
{
    public static final long POS_SEED = 1298319823120938102L;

    public static final int STRUCTURE_TRIES = 10;

    private static final ConcurrentMap<Long, ReentrantLock> CHUNK_LOCKS = new ConcurrentHashMap<>();

    private static long chunkKey(ChunkPos chunkPos)
    {
        return chunkKey(chunkPos.x, chunkPos.z);
    }

    private static long chunkKey(int x, int z)
    {
        return ((long) x & 0xffffffffL) << 32 | ((long) z & 0xffffffffL);
    }

    private static List<ReentrantLock> lockChunks(Stream<ChunkPos> chunkPositions)
    {
        List<Long> keys = chunkPositions
                .map(WorldGenStructures::chunkKey)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<ReentrantLock> locks = new ArrayList<>(keys.size());
        for (Long key : keys)
        {
            ReentrantLock lock = CHUNK_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
            lock.lock();
            locks.add(lock);
        }

        return locks;
    }

    private static void unlockChunks(List<ReentrantLock> locks)
    {
        for (int i = locks.size() - 1; i >= 0; i--)
            locks.get(i).unlock();
    }

    public static void planStaticStructuresInChunk(Random random, ChunkPos chunkPos, WorldServer world, BlockPos spawnPos, @Nullable Predicate<Structure> structurePredicate)
    {
        StaticGeneration.structuresAt(StructureRegistry.INSTANCE, chunkPos, world, spawnPos).forEach(triple ->
        {
            StaticGeneration staticGenInfo = triple.getMiddle();
            Structure<?> structure = triple.getLeft();
            BlockSurfacePos pos = triple.getRight();

            if (structurePredicate != null && !structurePredicate.test(structure))
                return;

            new StructureGenerator<>(structure).world(world).generationInfo(staticGenInfo)
                    .seed(random.nextLong()).randomPosition(pos, staticGenInfo.placer).fromCenter(true)
                    .partially(RecurrentComplex.PARTIALLY_SPAWN_NATURAL_STRUCTURES, chunkPos)
                    .generate();
        });
    }

    protected static float distance(ChunkPos left, ChunkPos right)
    {
        return MathHelper.sqrt(
                (left.x - right.x) * (left.x - right.x) +
                        (left.z - right.z) * (left.z - right.z));
    }

    public static void planStructuresInChunk(Random random, ChunkPos chunkPos, WorldServer world, Biome biomeGen, @Nullable Predicate<Structure> structurePredicate)
    {
        MixingStructureSelector<NaturalGeneration, NaturalStructureSelector.Category> structureSelector = NaturalGeneration.selectors(StructureRegistry.INSTANCE).get(biomeGen, world.provider);

        float distanceToSpawn = distance(new ChunkPos(world.getSpawnPoint()), chunkPos);
        // TODO Use STRUCTURE_TRIES
        List<Pair<Structure<?>, NaturalGeneration>> generated = structureSelector.generatedStructures(random, world.getBiome(chunkPos.getBlock(0, 0, 0)), world.provider, distanceToSpawn);

        generated.stream()
                .filter(pair -> structurePredicate == null || structurePredicate.test(pair.getLeft()))
                .forEach(pair -> planStructureInChunk(chunkPos, world, pair.getLeft(), pair.getRight(), random.nextLong()));
    }

    public static boolean generateOneStructureInChunk(Random random, ChunkPos chunkPos, WorldServer world, Biome biomeGen)
    {
        MixingStructureSelector<NaturalGeneration, NaturalStructureSelector.Category> structureSelector = NaturalGeneration.selectors(StructureRegistry.INSTANCE).get(biomeGen, world.provider);

        float distanceToSpawn = distance(new ChunkPos(world.getSpawnPoint()), chunkPos);

        for (int i = 0; i < STRUCTURE_TRIES; i++)
        {
            Pair<Structure<?>, NaturalGeneration> pair = structureSelector.selectOne(random, world.provider, world.getBiome(chunkPos.getBlock(0, 0, 0)), null, distanceToSpawn);

            if (pair != null)
            {
                if (planStructureInChunk(chunkPos, world, pair.getLeft(), pair.getRight(), random.nextLong()))
                    return true;
            }
        }

        return false;
    }

    // TODO Use !instantly to only plan structure but later generate
    protected static boolean planStructureInChunk(ChunkPos chunkPos, WorldServer world, Structure<?> structure, NaturalGeneration naturalGenInfo, long seed)
    {
        String structureName = StructureRegistry.INSTANCE.id(structure);

        BlockSurfacePos genPos = randomSurfacePos(chunkPos, seed);

        if (!naturalGenInfo.hasLimitations() || naturalGenInfo.getLimitations().areResolved(world, structureName))
        {
            StructureGenerator<?> generator = new StructureGenerator<>(structure).world(world).generationInfo(naturalGenInfo)
                    .seed(seed).maturity(StructureSpawnContext.GenerateMaturity.SUGGEST)
                    .randomPosition(genPos, naturalGenInfo.placer).fromCenter(true)
                    .partially(RecurrentComplex.PARTIALLY_SPAWN_NATURAL_STRUCTURES, chunkPos);

            if (naturalGenInfo.getGenerationWeight(world.provider, generator.environment().biome) <= 0)
            {
                RecurrentComplex.logger.trace(String.format("%s failed to spawn at %s (incompatible biome edge)", structure, genPos));
                return false;
            }

            return generator.generate().succeeded();
        }

        return false;
    }

    public static void complementStructuresInChunk(final ChunkPos chunkPos, final WorldServer world, List<WorldStructureGenerationData.StructureEntry> complement)
    {
        WorldStructureGenerationData data = WorldStructureGenerationData.get(world);

        for (WorldStructureGenerationData.StructureEntry entry : complement)
        {
            if (entry.preventComplementation())
                continue;

            Set<ChunkPos> relevantChunks = new HashSet<>(entry.rasterize());
            relevantChunks.add(chunkPos);

            List<ReentrantLock> locks = lockChunks(relevantChunks.stream());
            try
            {
                Structure<?> structure = StructureRegistry.INSTANCE.get(entry.getStructureID());

                if (structure == null)
                {
                    RecurrentComplex.logger.warn(String.format("Can't find structure %s (%s) to complement in %s (%d)", entry.getStructureID(), entry.getUuid(), chunkPos, world.provider.getDimension()));
                    continue;
                }

                if (entry.instanceData == null && !entry.firstTime)
                {
                    RecurrentComplex.logger.warn(String.format("Can't find instance data of %s (%s) to complement in %s (%d)", entry.getStructureID(), entry.getUuid(), chunkPos, world.provider.getDimension()));
                    continue;
                }

                new StructureGenerator<>(structure).world(world).generationInfo(entry.generationInfoID)
                        .seed(chunkSeed(entry.seed, chunkPos)).boundingBox(entry.boundingBox).transform(entry.transform).generationBB(Structures.chunkBoundingBox(chunkPos, true))
                        .structureID(entry.getStructureID()).instanceData(entry.instanceData)
                        // Could use entry.firstTime but then StructureGenerator would add a new entry
                        .maturity(StructureSpawnContext.GenerateMaturity.COMPLEMENT)
                        .generate();

                if (entry.firstTime)
                {
                    entry.firstTime = false;
                    data.markDirty();
                }
            }
            finally
            {
                unlockChunks(locks);
            }
        }
    }

    public static long chunkSeed(long seed, ChunkPos chunkPos)
    {
        // From world.setRandomSeed
        return (long) chunkPos.x * 341873128712L + (long) chunkPos.z * 132897987541L + seed;
    }

    public static BlockSurfacePos randomSurfacePos(ChunkPos chunkPos, long seed)
    {
        Random posRandom = new Random(seed ^ POS_SEED);
        // + 8 because, see this decoration explanation: https://www.reddit.com/r/feedthebeast/comments/5x0twz/investigating_extreme_worldgen_lag/
        // TL;DR chunks are expected to populate on the +xz edge to avoid accidentally loading neighboring chunks.
        // To facilitate this, chunks are only populated when its +x, +z, and +x+z neighbors are already provided.
        // So when we populate, we generate in this 'safe radius' and should have +- 8 blocks to play with before
        // triggering a new chunk to populate.
        return BlockSurfacePos.from(chunkPos.getBlock(
                posRandom.nextInt(16) + 8,
                0,
                posRandom.nextInt(16) + 8
        ));
    }

    public static boolean decorate(WorldServer world, Random random, ChunkPos chunkPos, @Nullable Predicate<Structure> structurePredicate)
    {
        boolean generated = false;

        boolean worldWantsStructures = world.getWorldInfo().isMapFeaturesEnabled();
        WorldStructureGenerationData data = WorldStructureGenerationData.get(world);

        // We need to synchronize (multithreaded gen) since we need to plan structures before complementing,
        // otherwise structures get lost in some chunks
        List<ReentrantLock> chunkLocks = lockChunks(Stream.of(chunkPos));
        try
        {
            List<WorldStructureGenerationData.StructureEntry> complement = data.structureEntriesIn(chunkPos).collect(Collectors.toList());
            if (structurePredicate == null)
                data.checkChunk(chunkPos);

            if (structurePredicate == null)
                complementStructuresInChunk(chunkPos, world, complement);

            if ((!RCConfig.honorStructureGenerationOption || worldWantsStructures)
                    && (structurePredicate == null || !RecurrentComplex.PARTIALLY_SPAWN_NATURAL_STRUCTURES || data.checkChunkFinal(chunkPos)))
            {
                Biome biomeGen = world.getBiome(chunkPos.getBlock(8, 0, 8));
                BlockPos spawnPos = world.getSpawnPoint();

                planStaticStructuresInChunk(random, chunkPos, world, spawnPos, structurePredicate);

                boolean mayGenerate = RCConfig.isGenerationEnabled(biomeGen) && RCConfig.isGenerationEnabled(world.provider);

                if (world.provider.getDimension() == 0)
                {
                    double distToSpawn = IvVecMathHelper.distanceSQ(new double[]{chunkPos.x * 16 + 8, chunkPos.z * 16 + 8}, new double[]{spawnPos.getX(), spawnPos.getZ()});
                    mayGenerate &= distToSpawn >= RCConfig.minDistToSpawnForGeneration * RCConfig.minDistToSpawnForGeneration;
                }

                if (mayGenerate)
                    planStructuresInChunk(random, chunkPos, world, biomeGen, structurePredicate);

                generated = true;
            }
        }
        finally
        {
            unlockChunks(chunkLocks);
        }

        return generated;
    }
}
