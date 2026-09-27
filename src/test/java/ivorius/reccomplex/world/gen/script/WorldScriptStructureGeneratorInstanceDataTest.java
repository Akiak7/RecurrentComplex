package ivorius.reccomplex.world.gen.script;

import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.reccomplex.RCConfig;
import ivorius.reccomplex.nbt.NBTStorable;
import ivorius.reccomplex.world.gen.feature.structure.Environment;
import ivorius.reccomplex.world.gen.feature.structure.ReadableInstanceData;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.context.StructureSpawnContext;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerMulti;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.junit.*;

import java.lang.reflect.Field;
import java.util.Random;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Replays child generation across two clips without a running game or terrain reads. */
public class WorldScriptStructureGeneratorInstanceDataTest
{
    private Field universal;
    private Object previous;
    private TransformerMulti transformer;

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Before public void setup() throws Exception
    {
        universal = RCConfig.class.getDeclaredField("universalTransformer");
        universal.setAccessible(true);
        previous = universal.get(null);
        transformer = mock(TransformerMulti.class);
        universal.set(null, transformer);
    }

    @After public void restore() throws Exception { universal.set(null, previous); }

    @Test public void preparedChildLayoutIsReusedAcrossChunkPasses()
    {
        NBTStorable prepared = mock(NBTStorable.class, "originally selected floor layout");
        ReadableInstanceData<NBTStorable> data = new ReadableInstanceData<>();
        data.setInstanceData(prepared);
        Structure<NBTStorable> structure = structure();
        generateTwoClips(structure, data);
        verify(structure, times(2)).generate(any(), same(prepared), same(transformer));
        verify(structure, never()).prepareInstanceData(any(), any());
        verify(structure, never()).loadInstanceData(any(), any(), any());
    }

    @Test public void serializedChildLayoutIsUsedOnEveryPassWithoutRerollingOrDoubleLoading()
    {
        NBTStorable restored = mock(NBTStorable.class, "restored selected floor layout");
        NBTTagCompound layout = new NBTTagCompound();
        layout.setString("chosenFloor", "BasicFloor");
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("layout", layout);
        ReadableInstanceData<NBTStorable> data = new ReadableInstanceData<>();
        data.readFromNBT("layout", saved);
        Structure<NBTStorable> structure = structure();
        when(structure.loadInstanceData(any(), eq(layout), same(transformer))).thenReturn(restored);
        generateTwoClips(structure, data);
        verify(structure, times(2)).generate(any(), same(restored), same(transformer));
        verify(structure, times(2)).loadInstanceData(any(), eq(layout), same(transformer));
        verify(structure, never()).prepareInstanceData(any(), any());
    }

    @SuppressWarnings("unchecked")
    private Structure<NBTStorable> structure()
    {
        Structure<NBTStorable> structure = mock(Structure.class);
        when(structure.size()).thenReturn(new int[]{51, 5, 51});
        // The broken path silently prepares a different layout, rather than crashing.
        when(structure.prepareInstanceData(any(), any())).thenReturn(mock(NBTStorable.class, "rerolled layout"));
        return structure;
    }

    private void generateTwoClips(Structure<NBTStorable> structure, ReadableInstanceData<NBTStorable> data)
    {
        WorldServer world = mock(WorldServer.class);
        WorldScriptStructureGenerator script = new WorldScriptStructureGenerator();
        WorldScriptStructureGenerator.InstanceData child = new WorldScriptStructureGenerator.InstanceData();
        child.structureID = "test-floor";
        child.lowerCoord = new BlockPos(-70, 88, 502);
        child.structureTransform = AxisAlignedTransform2D.R0;
        for (int x : new int[]{-56, -40})
        {
            Random random = mock(Random.class);
            when(random.nextLong()).thenReturn((long) x);
            StructureSpawnContext context = new StructureSpawnContext(new Environment(world, null, null, null), random,
                    AxisAlignedTransform2D.R0, new StructureBoundingBox(-73, 74, 499, -17, 82, 555),
                    new StructureBoundingBox(x, 1, 520, x + 15, 512, 535), null, 0, false,
                    StructureSpawnContext.GenerateMaturity.COMPLEMENT);
            assertTrue(script.generate(context, child, structure, data, "test-list").succeeded());
            verify(random).nextLong(); // The existing child seed call, with no extra parent/worldgen RNG calls.
            verifyNoMoreInteractions(random);
        }
        verifyZeroInteractions(world); // No chunk provision or placement-height calculation is needed.
    }
}
