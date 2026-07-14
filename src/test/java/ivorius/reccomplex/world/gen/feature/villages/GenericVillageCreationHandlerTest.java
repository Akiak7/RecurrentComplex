package ivorius.reccomplex.world.gen.feature.villages;

import net.minecraft.util.EnumFacing;
import net.minecraft.world.gen.structure.StructureVillagePieces;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GenericVillageCreationHandlerTest
{
    @Test
    public void fixedFacingMismatchHasNoTransform()
    {
        assertNull(GenericVillagePiece.getTransform(EnumFacing.NORTH, false, false, EnumFacing.EAST, new Random(0)));
    }

    @Test
    public void temporaryRejectionDoesNotExhaustPiece()
    {
        StructureVillagePieces.PieceWeight piece = pieceWithOneSpawn();

        assertNull(GenericVillageCreationHandler.reject(piece, false));
        assertEquals(1, piece.villagePiecesSpawned);
    }

    @Test
    public void permanentRejectionExhaustsPiece()
    {
        StructureVillagePieces.PieceWeight piece = pieceWithOneSpawn();

        assertNull(GenericVillageCreationHandler.reject(piece, true));
        assertEquals(piece.villagePiecesLimit, piece.villagePiecesSpawned);
    }

    private static StructureVillagePieces.PieceWeight pieceWithOneSpawn()
    {
        StructureVillagePieces.PieceWeight piece = new StructureVillagePieces.PieceWeight(GenericVillagePiece.class, 1, 3);
        piece.villagePiecesSpawned = 1;
        return piece;
    }
}
