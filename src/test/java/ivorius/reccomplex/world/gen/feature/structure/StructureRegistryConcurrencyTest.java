package ivorius.reccomplex.world.gen.feature.structure;

import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.client.rendering.MazeVisualizationContext;
import ivorius.reccomplex.files.loading.LeveledRegistry;
import ivorius.reccomplex.gui.table.TableDelegate;
import ivorius.reccomplex.gui.table.TableNavigator;
import ivorius.reccomplex.gui.table.datasource.TableDataSource;
import ivorius.reccomplex.nbt.NBTStorable;
import ivorius.reccomplex.world.gen.feature.structure.Placer;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureLoadContext;
import ivorius.reccomplex.world.gen.feature.structure.context.StructurePrepareContext;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericStructure;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericVariableDomain;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.GenerationType;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerMulti;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class StructureRegistryConcurrencyTest
{
    @Test
    public void cachesAreBuiltOncePerGenerationType() throws Exception
    {
        StructureRegistry registry = new TestStructureRegistry();
        AtomicInteger generationCalls = new AtomicInteger();
        int structureCount = 10;

        for (int i = 0; i < structureCount; i++)
        {
            registry.register("structure_" + i, "test", new TestStructure("structure_" + i, generationCalls), true, LeveledRegistry.Level.INTERNAL);
        }

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++)
        {
            futures.add(executor.submit(() -> {
                try
                {
                    start.await();
                    Collection<Pair<Structure<?>, TestGenerationType>> result = registry.getGenerationTypes(TestGenerationType.class);
                    assertEquals(structureCount, result.size());
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return null;
            }));
        }

        start.countDown();

        for (Future<?> future : futures)
        {
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdownNow();

        assertEquals("Only one cache build should occur", structureCount, generationCalls.get());

        Collection<Pair<Structure<?>, TestGenerationType>> cached = registry.getGenerationTypes(TestGenerationType.class);
        assertEquals(structureCount, cached.size());

        Pair<Structure<?>, TestGenerationType> sample = cached.iterator().next();
        assertNotNull(sample);
        Assert.assertThrows(UnsupportedOperationException.class, () -> cached.add(sample));

        registry.invalidateCaches();

        Collection<Pair<Structure<?>, TestGenerationType>> rebuilt = registry.getGenerationTypes(TestGenerationType.class);
        assertEquals(structureCount, rebuilt.size());
        assertEquals("Cache should rebuild after invalidation", structureCount * 2, generationCalls.get());
    }

    @BeforeClass
    public static void setUpLogging()
    {
        if (RecurrentComplex.logger == null)
        {
            RecurrentComplex.logger = LogManager.getLogger("reccomplex-test");
        }
    }

    private static class TestStructure implements Structure<TestInstanceData>
    {
        private final List<TestGenerationType> generationTypes;
        private final AtomicInteger invocationCounter;

        private TestStructure(String id, AtomicInteger invocationCounter)
        {
            this.invocationCounter = invocationCounter;
            this.generationTypes = Collections.singletonList(new TestGenerationType(id + "_generation"));
        }

        @Override
        public void generate(@Nonnull StructureSpawnContext context, @Nonnull TestInstanceData instanceData, @Nonnull TransformerMulti transformer)
        {
        }

        @Nullable
        @Override
        public TestInstanceData prepareInstanceData(@Nonnull StructurePrepareContext context, @Nonnull TransformerMulti transformer)
        {
            return new TestInstanceData();
        }

        @Nonnull
        @Override
        public TestInstanceData loadInstanceData(@Nonnull StructureLoadContext context, @Nonnull NBTBase nbt, @Nonnull TransformerMulti transformer)
        {
            return new TestInstanceData();
        }

        @Nonnull
        @Override
        public <I extends GenerationType> List<I> generationTypes(@Nonnull Class<? extends I> clazz)
        {
            invocationCounter.incrementAndGet();
            if (clazz.isAssignableFrom(TestGenerationType.class))
            {
                //noinspection unchecked
                return (List<I>) generationTypes;
            }
            return Collections.emptyList();
        }

        @Override
        public GenerationType generationType(@Nonnull String id)
        {
            return generationTypes.stream().filter(type -> type.id().equals(id)).findFirst().orElse(null);
        }

        @Nonnull
        @Override
        public int[] size()
        {
            return new int[]{1, 1, 1};
        }

        @Override
        public boolean isRotatable()
        {
            return false;
        }

        @Override
        public boolean isMirrorable()
        {
            return false;
        }

        @Override
        public boolean isBlocking()
        {
            return false;
        }

        @Nullable
        @Override
        public GenericStructure copyAsGenericStructure()
        {
            return null;
        }

        @Override
        public boolean areDependenciesResolved()
        {
            return true;
        }

        @Nullable
        @Override
        public IvBlockCollection blockCollection()
        {
            return null;
        }

        @Nonnull
        @Override
        public GenericVariableDomain declaredVariables()
        {
            return new GenericVariableDomain();
        }
    }

    private static class TestInstanceData implements NBTStorable
    {
        @Override
        public NBTBase writeToNBT()
        {
            return new NBTTagCompound();
        }
    }

    private static class TestGenerationType extends GenerationType
    {
        private TestGenerationType(String id)
        {
            super(id);
        }

        @Override
        public String displayString()
        {
            return id();
        }

        @Nullable
        @Override
        public Placer placer()
        {
            return null;
        }

        @Nullable
        @Override
        @SideOnly(Side.CLIENT)
        public TableDataSource tableDataSource(MazeVisualizationContext mazeVisualizationContext, TableNavigator navigator, TableDelegate delegate)
        {
            return null;
        }
    }

    private static class TestStructureRegistry extends StructureRegistry
    {
        @Override
        public Structure register(String id, String domain, Structure structure, boolean active, LeveledRegistry.ILevel level)
        {
            return super.register(id, domain, structure, active && structure.areDependenciesResolved(), level);
        }
    }
}
