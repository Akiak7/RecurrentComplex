package ivorius.reccomplex.world.gen.script;

import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import net.minecraft.util.EnumFacing;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WorldScriptStructureGeneratorTest
{
    @Test
    public void transformsListFrontWithParent()
    {
        for (EnumFacing front : EnumFacing.HORIZONTALS)
        {
            for (int rotation = 0; rotation < 4; rotation++)
            {
                for (boolean mirror : new boolean[]{false, true})
                {
                    AxisAlignedTransform2D transform = AxisAlignedTransform2D.from(rotation, mirror);

                    assertEquals(transform.apply(front), WorldScriptStructureGenerator.transformedListFront(front, transform));
                }
            }
        }
    }

    @Test
    public void preservesNullListFront()
    {
        assertNull(WorldScriptStructureGenerator.transformedListFront(null, AxisAlignedTransform2D.R1_F));
    }

    @Test
    public void fixedFacingCandidateRemainsUnrotated()
    {
        for (EnumFacing savedFront : EnumFacing.HORIZONTALS)
        {
            for (EnumFacing transformedFront : EnumFacing.HORIZONTALS)
            {
                assertEquals(0, WorldScriptStructureGenerator.listStructureRotations(false, savedFront, transformedFront, false));
            }
        }
    }

    @Test
    public void rotatableCandidateAlignsWithTransformedFront()
    {
        for (EnumFacing savedFront : EnumFacing.HORIZONTALS)
        {
            for (EnumFacing transformedFront : EnumFacing.HORIZONTALS)
            {
                for (boolean mirror : new boolean[]{false, true})
                {
                    int rotations = WorldScriptStructureGenerator.listStructureRotations(true, savedFront, transformedFront, mirror);
                    AxisAlignedTransform2D transform = AxisAlignedTransform2D.from(rotations, mirror);

                    assertEquals(transformedFront, transform.apply(savedFront));
                }
            }
        }
    }
}
