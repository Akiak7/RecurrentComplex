package ivorius.reccomplex.world.gen.feature.villages;

import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureVillagePieces;
import org.junit.BeforeClass;
import org.junit.Test;
import net.minecraftforge.fml.common.registry.VillagerRegistry;
import ivorius.reccomplex.files.loading.LeveledRegistry;
import ivorius.reccomplex.world.gen.feature.structure.Structure;
import ivorius.reccomplex.world.gen.feature.structure.StructureRegistry;
import ivorius.reccomplex.world.gen.feature.structure.generic.generation.VanillaGeneration;
import net.minecraft.world.gen.structure.StructureBoundingBox;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Retains the old exhaustion-only reproducer and tests retirement through the real vanilla selector. */
public class VillageLampFallbackCharacterizationTest
{
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void exhaustedRcEntryStillConsumesVanillaSelectionAttempts() throws Exception
    {
        StructureVillagePieces.PieceWeight rejected = new StructureVillagePieces.PieceWeight(GenericVillagePiece.class, 9, 1);
        StructureVillagePieces.PieceWeight valid = new StructureVillagePieces.PieceWeight(StructureVillagePieces.House4Garden.class, 1, 3);
        new GenericVillageCreationHandler("unavailable", "village").kill(rejected);
        StructureVillagePieces.Start start = start(rejected, valid);
        CountingRandom random = new CountingRandom();

        StructureVillagePieces.Village result = choose(start, random);

        assertTrue(result instanceof StructureVillagePieces.Torch);
        assertEquals(5, random.weightedDraws);
        assertEquals(0, valid.villagePiecesSpawned);
        assertTrue(start.structureVillageWeightedPieceList.contains(rejected));
    }

    @Test public void sameChoiceProducesAHouseWhenRejectedWeightIsAbsent() throws Exception
    {
        StructureVillagePieces.PieceWeight valid = new StructureVillagePieces.PieceWeight(StructureVillagePieces.House4Garden.class, 1, 3);
        StructureVillagePieces.Village result = choose(start(valid), new CountingRandom());
        assertTrue(result instanceof StructureVillagePieces.House4Garden);
        assertEquals(1, valid.villagePiecesSpawned);
    }

    @Test public void permanentRejectionRetiresOnlyItsEntryWithoutInvalidatingAnActiveIterator() throws Exception
    {
        StructureVillagePieces.PieceWeight rejected = new StructureVillagePieces.PieceWeight(MissingPiece.class, 9, 1);
        StructureVillagePieces.PieceWeight valid = new StructureVillagePieces.PieceWeight(StructureVillagePieces.House4Garden.class, 1, 3);
        StructureVillagePieces.PieceWeight unrelated = new StructureVillagePieces.PieceWeight(GenericVillagePiece.class, 7, 0);
        StructureVillagePieces.Start start = start(rejected, valid, unrelated);
        List<StructureVillagePieces.PieceWeight> original = start.structureVillageWeightedPieceList;
        Iterator<StructureVillagePieces.PieceWeight> iterator = original.iterator();
        assertSame(rejected, iterator.next());
        Random random = mock(Random.class);
        assertNull(missingHandler().buildComponent(rejected, start, Collections.emptyList(), random, 0, 64, 0, EnumFacing.NORTH, 1));
        assertSame(valid, iterator.next());
        assertSame(unrelated, iterator.next());
        assertEquals(Arrays.asList(valid, unrelated), start.structureVillageWeightedPieceList);
        assertEquals(Arrays.asList(rejected, valid, unrelated), original);
        assertEquals(rejected.villagePiecesLimit, rejected.villagePiecesSpawned);
        verifyZeroInteractions(random);
    }

    @Test public void retirementInsideRealSelectorPreservesIterationAndSuccessfulFollowingHouse() throws Exception
    {
        withMissingHandler(() -> {
            StructureVillagePieces.PieceWeight rejected = new StructureVillagePieces.PieceWeight(MissingPiece.class, 9, 1);
            StructureVillagePieces.PieceWeight valid = new StructureVillagePieces.PieceWeight(StructureVillagePieces.House4Garden.class, 1, 3);
            StructureVillagePieces.Start start = start(rejected, valid);
            assertTrue(choose(start, new CountingRandom()) instanceof StructureVillagePieces.House4Garden);
            assertEquals(Collections.singletonList(valid), start.structureVillageWeightedPieceList);
            assertEquals(1, valid.villagePiecesSpawned);
            assertSame(valid, start.lastPlaced);
        });
    }

    @Test public void cachedWeightCanAffectCurrentCallButNotTheNextPosition() throws Exception
    {
        withMissingHandler(() -> {
            StructureVillagePieces.PieceWeight rejected = new StructureVillagePieces.PieceWeight(MissingPiece.class, 9, 1);
            StructureVillagePieces.PieceWeight valid = new StructureVillagePieces.PieceWeight(StructureVillagePieces.House4Garden.class, 1, 3);
            StructureVillagePieces.Start start = start(valid, rejected);
            Random chooseRejectedRange = new Random() {
                @Override public int nextInt(int bound) { return bound == 10 ? 1 : 0; }
            };
            // Vanilla caches total=10 before entering RC. Preserve its bounded fallback for this call.
            assertTrue(choose(start, chooseRejectedRange) instanceof StructureVillagePieces.Torch);
            assertEquals(Collections.singletonList(valid), start.structureVillageWeightedPieceList);
            assertTrue(choose(start, chooseRejectedRange) instanceof StructureVillagePieces.House4Garden);
            assertEquals(1, valid.villagePiecesSpawned);
        });
    }

    @Test public void multipleRejectionsAndAHouseReachingItsLimitCanRetireInOneIteration() throws Exception
    {
        withMissingHandler(() -> {
            StructureVillagePieces.PieceWeight first = new StructureVillagePieces.PieceWeight(MissingPiece.class, 9, 1);
            StructureVillagePieces.PieceWeight second = new StructureVillagePieces.PieceWeight(MissingPiece.class, 9, 1);
            StructureVillagePieces.PieceWeight valid = new StructureVillagePieces.PieceWeight(StructureVillagePieces.House4Garden.class, 1, 1);
            StructureVillagePieces.Start start = start(first, second, valid);
            List<StructureVillagePieces.PieceWeight> original = start.structureVillageWeightedPieceList;
            assertTrue(choose(start, new CountingRandom()) instanceof StructureVillagePieces.House4Garden);
            assertTrue(start.structureVillageWeightedPieceList.isEmpty());
            assertEquals(3, original.size()); // No structural modification under the live iterator.
            assertEquals(1, first.villagePiecesSpawned);
            assertEquals(1, second.villagePiecesSpawned);
            assertEquals(1, valid.villagePiecesSpawned);
        });
    }

    @Test public void retiringLastUnavailableEntryLeavesNoFurtherFallbacks() throws Exception
    {
        withMissingHandler(() -> {
            StructureVillagePieces.PieceWeight rejected = new StructureVillagePieces.PieceWeight(MissingPiece.class, 9, 1);
            StructureVillagePieces.Start start = start(rejected);
            choose(start, new CountingRandom());
            assertTrue(start.structureVillageWeightedPieceList.isEmpty());
            Random random = mock(Random.class);
            assertNull(choose(start, random));
            verifyZeroInteractions(random);
        });
    }

    @Test public void invalidGenerationAndBiomeRetireButOrientationAndCollisionRemainEligible()
    {
        for (String reason : Arrays.asList("generation", "biome", "orientation", "collision"))
        {
            String id = "lamp-retirement-test-" + reason;
            Structure<?> structure = mock(Structure.class);
            VanillaGeneration generation = mock(VanillaGeneration.class);
            if (!reason.equals("generation")) when(structure.generationType("village")).thenReturn(generation);
            when(generation.generatesIn(any())).thenReturn(!reason.equals("biome"));
            generation.front = reason.equals("orientation") ? EnumFacing.NORTH : null;
            when(structure.size()).thenReturn(new int[]{3, 3, 3});
            StructureRegistry.INSTANCE.register(id, "test", structure, false, LeveledRegistry.Level.CUSTOM);
            try
            {
                StructureVillagePieces.PieceWeight piece = new StructureVillagePieces.PieceWeight(GenericVillagePiece.class, 1, 3);
                StructureVillagePieces.Start start = start(piece);
                List<StructureVillagePieces.PieceWeight> original = start.structureVillageWeightedPieceList;
                StructureComponent obstacle = mock(StructureComponent.class);
                when(obstacle.getBoundingBox()).thenReturn(new StructureBoundingBox(0, 64, 0, 2, 66, 2));
                Random random = mock(Random.class);
                assertNull(new GenericVillageCreationHandler(id, "village").buildComponent(piece, start,
                        Collections.singletonList(obstacle), random, 0, 64, 0, EnumFacing.EAST, 1));
                boolean permanent = reason.equals("generation") || reason.equals("biome");
                assertEquals(reason, permanent, start.structureVillageWeightedPieceList.isEmpty());
                assertEquals(permanent ? 3 : 0, piece.villagePiecesSpawned);
                if (!permanent) assertSame(original, start.structureVillageWeightedPieceList);
                verifyZeroInteractions(random);
            }
            finally { StructureRegistry.INSTANCE.unregister(id, LeveledRegistry.Level.CUSTOM); }
        }
    }

    private static GenericVillageCreationHandler missingHandler()
    {
        return new GenericVillageCreationHandler("missing-lamp-regression-fixture", "village") {
            @Override public Class<? extends StructureVillagePieces.Village> getComponentClass() { return MissingPiece.class; }
        };
    }

    private interface CheckedRunnable { void run() throws Exception; }
    private static void withMissingHandler(CheckedRunnable action) throws Exception
    {
        Map<Class<?>, VillagerRegistry.IVillageCreationHandler> handlers = TemporaryVillagerRegistry.getMap();
        VillagerRegistry.IVillageCreationHandler previous = handlers.get(MissingPiece.class);
        VillagerRegistry.instance().registerVillageCreationHandler(missingHandler());
        try { action.run(); }
        finally { if (previous == null) handlers.remove(MissingPiece.class); else handlers.put(MissingPiece.class, previous); }
    }

    public static class MissingPiece extends GenericVillagePiece { }

    private static StructureVillagePieces.Start start(StructureVillagePieces.PieceWeight... entries)
    {
        StructureVillagePieces.Start start = new StructureVillagePieces.Start();
        start.structureVillageWeightedPieceList = new ArrayList<>(Arrays.asList(entries));
        return start;
    }

    private static StructureVillagePieces.Village choose(StructureVillagePieces.Start start, Random random) throws Exception
    {
        Method choose = StructureVillagePieces.class.getDeclaredMethod("generateComponent", StructureVillagePieces.Start.class,
                List.class, Random.class, int.class, int.class, int.class, EnumFacing.class, int.class);
        choose.setAccessible(true);
        return (StructureVillagePieces.Village) choose.invoke(null, start, Collections.<StructureComponent>emptyList(), random,
                0, 64, 0, EnumFacing.NORTH, 1);
    }

    private static class CountingRandom extends Random
    {
        int weightedDraws;
        @Override public int nextInt(int bound) { if (bound == 10) weightedDraws++; return 0; }
    }
}
