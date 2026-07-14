package ivorius.reccomplex.world.gen.feature.structure.generic.generation;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ListGenerationTest
{
    private static final String LIST_ID = "children";

    @Test
    public void fixedCandidateMustMatchTransformedFront()
    {
        ListGeneration north = generation(EnumFacing.NORTH);

        assertTrue(ListGeneration.matches(LIST_ID, EnumFacing.NORTH, north, false));
        assertFalse(ListGeneration.matches(LIST_ID, EnumFacing.EAST, north, false));
    }

    @Test
    public void rotatableCandidateAcceptsEveryTransformedFront()
    {
        ListGeneration north = generation(EnumFacing.NORTH);

        for (EnumFacing transformedFront : EnumFacing.HORIZONTALS)
            assertTrue(ListGeneration.matches(LIST_ID, transformedFront, north, true));
    }

    @Test
    public void missingRequestedFrontAcceptsFixedCandidate()
    {
        assertTrue(ListGeneration.matches(LIST_ID, null, generation(EnumFacing.SOUTH), false));
    }

    @Test
    public void candidateMustBelongToRequestedList()
    {
        assertFalse(ListGeneration.matches("other", EnumFacing.NORTH, generation(EnumFacing.NORTH), true));
    }

    private static ListGeneration generation(EnumFacing front)
    {
        return new ListGeneration("test", LIST_ID, 1.0, BlockPos.ORIGIN, front);
    }
}
