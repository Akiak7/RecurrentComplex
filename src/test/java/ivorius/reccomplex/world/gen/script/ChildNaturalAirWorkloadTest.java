package ivorius.reccomplex.world.gen.script;

import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.ivtoolkit.tools.IvWorldData;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.nbt.NBTStorable;
import ivorius.reccomplex.world.gen.feature.StructureGenerator;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.ReadableInstanceData;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.Transformer;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerMulti;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerNaturalAir;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.storage.MapStorage;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.junit.*;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Counts real generator dispatch and real Natural Air AFTER work with controlled child layouts. */
public class ChildNaturalAirWorkloadTest
{
    private Field universal;
    private Object previous;
    private Logger previousLogger;
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }
    @Before public void setup() throws Exception
    {
        universal = RCConfig.class.getDeclaredField("universalTransformer");
        universal.setAccessible(true);
        previous = universal.get(null);
        universal.set(null, mock(TransformerMulti.class));
        previousLogger = RecurrentComplex.logger;
        RecurrentComplex.logger = mock(Logger.class);
    }
    @After public void restore() throws Exception { universal.set(null, previous); RecurrentComplex.logger = previousLogger; }

    @Test public void fixedLayoutHasSameAfterPassesAndReadsWithLessRepreparation()
    {
        for (boolean saved : new boolean[]{false, true})
        {
            Counts old = run(true, saved, 0), fixed = run(false, saved, 0);
            assertEquals(3, old.passes); assertEquals(old.passes, fixed.passes);
            assertEquals(old.clouds, fixed.clouds);
            assertEquals(old.reads, fixed.reads);
            assertEquals(saved ? 2 : 3, old.prepares); assertEquals(0, fixed.prepares);
            assertEquals(saved ? 2 : 0, old.loads); assertEquals(saved ? 3 : 0, fixed.loads);
            report(saved, 0, old, fixed);
        }
    }

    @Test public void rerolledLayoutsCanChangeWorkloadWithoutChangingPassCount()
    {
        for (boolean saved : new boolean[]{false, true}) for (int difference : new int[]{-1, 1})
        {
            Counts old = run(true, saved, difference), fixed = run(false, saved, difference);
            assertEquals(old.passes, fixed.passes);
            assertNotEquals(old.clouds, fixed.clouds);
            assertTrue(difference > 0 ? old.reads > fixed.reads : old.reads < fixed.reads);
            assertEquals(difference > 0 ? Arrays.asList(1, 1, 1) : Arrays.asList(8, 8, 8), fixed.clouds);
            report(saved, difference, old, fixed);
        }
    }

    private static TransformerNaturalAir.InstanceData layout(boolean larger)
    {
        TransformerNaturalAir.InstanceData data = new TransformerNaturalAir.InstanceData();
        for (int x = 0; x < (larger ? 2 : 1); x++)
            for (int y = 0; y < (larger ? 2 : 1); y++)
                for (int z = 0; z < (larger ? 2 : 1); z++) data.cloud.put(new BlockPos(x, y, z), 1);
        return data;
    }

    @SuppressWarnings("unchecked")
    private Counts run(boolean legacy, boolean saved, int difference)
    {
        Counts counts = new Counts();
        MapStorage storage = new MapStorage(null);
        WorldServer world = mock(WorldServer.class, withSettings().stubOnly().defaultAnswer(invocation -> {
            if (invocation.getMethod().getName().equals("getBlockState")) { counts.reads++; return Blocks.AIR.getDefaultState(); }
            if (invocation.getMethod().getName().equals("getPerWorldStorage")) return storage;
            throw new AssertionError("Unexpected world/chunk access: " + invocation.getMethod());
        }));
        try
        {
            Field provider = World.class.getDeclaredField("provider");
            provider.setAccessible(true);
            provider.set(world, mock(WorldProvider.class));
        }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        TransformerNaturalAir naturalAir = mock(TransformerNaturalAir.class, CALLS_REAL_METHODS);
        IvWorldData worldData = new IvWorldData(new IvBlockCollection(3, 3, 3), Collections.emptyList(), Collections.emptyList());
        Structure<TransformerNaturalAir.InstanceData> structure = mock(Structure.class);
        when(structure.size()).thenReturn(new int[]{3, 3, 3});
        when(structure.prepareInstanceData(any(), any())).thenAnswer(i -> { counts.prepares++; return layout(difference > 0); });
        when(structure.loadInstanceData(any(), any(), any())).thenAnswer(i -> {
            counts.loads++;
            TransformerNaturalAir.InstanceData data = new TransformerNaturalAir.InstanceData();
            data.readFromNBT(i.getArgument(1));
            return data;
        });
        doAnswer(i -> {
            TransformerNaturalAir.InstanceData data = i.getArgument(1);
            counts.passes++; counts.clouds.add(data.cloud.size());
            naturalAir.transform(data, Transformer.Phase.AFTER, i.getArgument(0), worldData, null);
            return null;
        }).when(structure).generate(any(), any(), any());
        ReadableInstanceData<TransformerNaturalAir.InstanceData> data = new ReadableInstanceData<>();
        if (saved)
        {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setTag("layout", layout(difference < 0).writeToNBT());
            data.readFromNBT("layout", tag);
        }
        else data.setInstanceData(layout(difference < 0));
        WorldScriptStructureGenerator script = legacy ? new LegacyScript() : new WorldScriptStructureGenerator();
        WorldScriptStructureGenerator.InstanceData child = new WorldScriptStructureGenerator.InstanceData();
        child.structureID = "workload-child";
        child.lowerCoord = new BlockPos(-70, 88, -502);
        child.structureTransform = AxisAlignedTransform2D.R0;
        for (int pass = 0; pass < 3; pass++)
        {
            Random random = mock(Random.class);
            when(random.nextLong()).thenReturn(100L + pass);
            StructureSpawnContext context = new StructureSpawnContext(new Environment(world, null, null, null), random,
                    AxisAlignedTransform2D.R0, new StructureBoundingBox(-73, 74, -505, -17, 82, -449),
                    new StructureBoundingBox(-72 + 16*pass, 1, -504, -57 + 16*pass, 254, -489), null, 0, false,
                    pass == 0 ? StructureSpawnContext.GenerateMaturity.FIRST : StructureSpawnContext.GenerateMaturity.COMPLEMENT);
            assertTrue(script.generate(context, child, structure, data, "test-list").succeeded());
            verify(random).nextLong(); verifyNoMoreInteractions(random);
        }
        return counts;
    }

    private static void report(boolean saved, int difference, Counts old, Counts fixed)
    {
        System.out.println("child saved=" + saved + " rerollSizeDifference=" + difference + " legacy=" + old + " fixed=" + fixed);
    }

    private static class Counts
    {
        int prepares, loads, passes, reads;
        List<Integer> clouds = new ArrayList<>();
        @Override public String toString() { return "{prepare="+prepares+",load="+loads+",after="+passes+",clouds="+clouds+",reads="+reads+"}"; }
    }

    private static class LegacyScript extends WorldScriptStructureGenerator
    {
        @Override protected <T extends NBTStorable> StructureGenerator.GenerationResult generate(StructureSpawnContext context,
                InstanceData data, Structure<T> structure, ReadableInstanceData<T> readable, String generationInfo)
        {
            StructureGenerator<T> generator = new StructureGenerator<>(structure).structureID(data.structureID).asChild(context)
                    .generationInfo(generationInfo).lowerCoord(data.lowerCoord).transform(data.structureTransform);
            readable.load(generator); // Exact pre-fix binding path.
            return generator.generate();
        }
    }
}
