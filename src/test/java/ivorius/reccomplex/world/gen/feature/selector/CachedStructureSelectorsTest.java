package ivorius.reccomplex.world.gen.feature.selector;

import ivorius.reccomplex.client.rendering.MazeVisualizationContext;
import ivorius.reccomplex.gui.table.TableDelegate;
import ivorius.reccomplex.gui.table.TableNavigator;
import ivorius.reccomplex.gui.table.datasource.TableDataSource;
import ivorius.reccomplex.world.gen.feature.structure.Placer;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.GenerationType;
import ivorius.reccomplex.dimensions.DimensionDictionary;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.DimensionType;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CachedStructureSelectorsTest
{
    @Test
    public void getIsThreadSafeAndCachesSelectors() throws Exception
    {
        AtomicInteger creations = new AtomicInteger();
        CachedStructureSelectors<TestStructureSelector> cache = new CachedStructureSelectors<>((biome, provider) ->
        {
            creations.incrementAndGet();
            return new TestStructureSelector(provider, biome);
        });

        TestBiome biome = new TestBiome();
        WorldProvider provider = new TestWorldProvider();

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        Set<TestStructureSelector> selectors = Collections.newSetFromMap(new ConcurrentHashMap<>());
        List<Future<?>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++)
        {
            futures.add(executor.submit(() ->
            {
                for (int j = 0; j < 100; j++)
                {
                    TestStructureSelector selector = cache.get(biome, provider);
                    Assert.assertNotNull(selector);
                    selectors.add(selector);
                }
            }));
        }

        for (Future<?> future : futures)
        {
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();
        Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        Assert.assertEquals("Only one selector should have been created for identical lookups", 1, selectors.size());
        Assert.assertEquals("Supplier should have been invoked exactly once", 1, creations.get());

        TestStructureSelector first = selectors.iterator().next();
        first.invalidate();

        TestStructureSelector rebuilt = cache.get(biome, provider);
        Assert.assertNotSame("Invalid selector must be replaced", first, rebuilt);
        Assert.assertEquals("Supplier should have been invoked a second time after invalidation", 2, creations.get());
    }

    private static class TestStructureSelector extends StructureSelector<TestGenerationType, TestCategory>
    {
        private volatile boolean valid = true;

        TestStructureSelector(WorldProvider provider, Biome biome)
        {
            super(Collections.emptyMap(), provider, biome, TestGenerationType.class);
        }

        void invalidate()
        {
            valid = false;
        }

        @Override
        public boolean isValid(Biome biome, WorldProvider provider)
        {
            return valid;
        }
    }

    private static class TestGenerationType extends GenerationType implements EnvironmentalSelection<TestCategory>
    {
        protected TestGenerationType()
        {
            super("test");
        }

        @Override
        public double getGenerationWeight(WorldProvider provider, Biome biome)
        {
            return 0;
        }

        @Override
        public TestCategory generationCategory()
        {
            return TestCategory.INSTANCE;
        }

        @Override
        public String displayString()
        {
            return "test";
        }

        @Nullable
        @Override
        public Placer placer()
        {
            return null;
        }

        @SideOnly(Side.CLIENT)
        @Nullable
        @Override
        public TableDataSource tableDataSource(MazeVisualizationContext mazeVisualizationContext, TableNavigator navigator, TableDelegate delegate)
        {
            return null;
        }
    }

    private enum TestCategory
    {
        INSTANCE
    }

    private static class TestBiome extends Biome
    {
        protected TestBiome()
        {
            super(new Biome.BiomeProperties("test"));
        }
    }

    private static class TestWorldProvider extends WorldProvider implements DimensionDictionary.Handler
    {
        private final Set<String> dimensionTypes = Collections.singleton("test");

        private TestWorldProvider()
        {
            this.setDimension(0);
        }

        @Override
        public DimensionType getDimensionType()
        {
            return DimensionType.OVERWORLD;
        }

        @Override
        public IChunkGenerator createChunkGenerator()
        {
            return new IChunkGenerator()
            {
                @Override
                public Chunk generateChunk(int x, int z)
                {
                    return null;
                }

                @Override
                public void populate(int x, int z)
                {
                }

                @Override
                public boolean generateStructures(Chunk chunk, int x, int z)
                {
                    return false;
                }

                @Override
                public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos)
                {
                    return Collections.emptyList();
                }

                @Override
                public BlockPos getNearestStructurePos(World world, String structureName, BlockPos position, boolean findUnexplored)
                {
                    return null;
                }

                @Override
                public void recreateStructures(Chunk chunk, int x, int z)
                {
                }

                @Override
                public boolean isInsideStructure(World world, String structureName, BlockPos pos)
                {
                    return false;
                }
            };
        }

        @Override
        public Set<String> getDimensionTypes()
        {
            return dimensionTypes;
        }
    }
}
