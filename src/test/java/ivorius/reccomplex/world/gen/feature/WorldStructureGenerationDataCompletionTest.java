package ivorius.reccomplex.world.gen.feature;

import ivorius.ivtoolkit.math.AxisAlignedTransform2D;
import ivorius.reccomplex.utils.RCStructureBoundingBoxes;
import ivorius.reccomplex.world.gen.feature.structure.Structures;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class WorldStructureGenerationDataCompletionTest
{
    @Test
    public void saveReloadPreservesPendingCompletionAndCheckedChunks()
    {
        WorldStructureGenerationData original = new WorldStructureGenerationData();
        ChunkPos initial = new ChunkPos(0, 0);
        ChunkPos pending = new ChunkPos(1, 0);
        original.checkChunk(initial);
        original.checkChunkFinal(initial);
        WorldStructureGenerationData.StructureEntry entry = partialEntry();
        assertEquals(Collections.singleton(initial), original.addEntry(entry));

        WorldStructureGenerationData restored = new WorldStructureGenerationData();
        restored.readFromNBT(original.writeToNBT(new NBTTagCompound()));
        WorldStructureGenerationData.StructureEntry loaded = restored.structureEntriesIn(pending).findFirst().get();
        assertEquals(entry.getUuid(), loaded.getUuid());
        assertEquals("test-tower", loaded.getStructureID());
        assertEquals("natural", loaded.generationInfoID);
        assertArrayEquals(entry.boundingBox.toNBTTagIntArray().getIntArray(), loaded.boundingBox.toNBTTagIntArray().getIntArray());
        assertEquals(entry.seed, loaded.seed);
        assertEquals(entry.transform, loaded.transform);
        assertEquals(entry.instanceData, loaded.instanceData);
        assertFalse(loaded.firstTime);
        assertFalse(loaded.preventComplementation());
        assertTrue(restored.isChunkChecked(initial));
        assertTrue(restored.isChunkCheckedFinal(initial));
        assertFalse(restored.isChunkChecked(pending));
    }

    @Test
    public void removingPendingEntryLosesCompletionButKeepsCheckedFlags()
    {
        WorldStructureGenerationData data = new WorldStructureGenerationData();
        WorldStructureGenerationData.StructureEntry entry = partialEntry();
        data.checkChunk(new ChunkPos(0, 0));
        data.checkChunkFinal(new ChunkPos(0, 0));
        data.addEntry(entry);
        assertEquals(1L, data.structureEntriesIn(new ChunkPos(1, 0)).count());

        // Models the removeEntry call made by RLTweaker's periodic cleanup.
        data.removeEntry(entry.getUuid());
        assertEquals(0L, data.structureEntriesIn(new ChunkPos(1, 0)).count());
        assertTrue(data.getEntriesByID("test-tower").isEmpty());
        assertTrue(data.isChunkChecked(new ChunkPos(0, 0)));
        assertTrue(data.isChunkCheckedFinal(new ChunkPos(0, 0)));
    }

    @Test
    public void loadingWithoutCompletionTagsAlsoClearsCheckedFlags()
    {
        WorldStructureGenerationData data = new WorldStructureGenerationData();
        data.addEntry(partialEntry());
        data.checkChunk(new ChunkPos(0, 0));
        data.checkChunkFinal(new ChunkPos(0, 0));

        // Models the NBT edit made by RLTweaker's separate world-load cleanup.
        NBTTagCompound saved = data.writeToNBT(new NBTTagCompound());
        saved.removeTag("entries");
        saved.removeTag("checkedChunks");
        saved.removeTag("checkedChunksFinal");
        data.readFromNBT(saved);
        assertEquals(0L, data.structureEntriesIn(new ChunkPos(1, 0)).count());
        assertFalse(data.isChunkChecked(new ChunkPos(0, 0)));
        assertFalse(data.isChunkCheckedFinal(new ChunkPos(0, 0)));
    }

    @Test
    public void decorationBoundsCoverEveryColumnExactlyOnceAcrossNegativeCoordinates()
    {
        StructureBoundingBox bounds = new StructureBoundingBox(-25, 60, -9, 40, 80, 24);
        Set<ChunkPos> chunks = RCStructureBoundingBoxes.rasterize(bounds, true);
        for (int x = bounds.minX; x <= bounds.maxX; x++)
        {
            for (int z = bounds.minZ; z <= bounds.maxZ; z++)
            {
                final int columnX = x;
                final int columnZ = z;
                Set<ChunkPos> covering = chunks.stream().filter(chunk -> {
                    StructureBoundingBox clip = Structures.chunkBoundingBox(chunk, true);
                    return columnX >= clip.minX && columnX <= clip.maxX
                            && columnZ >= clip.minZ && columnZ <= clip.maxZ;
                }).collect(Collectors.toSet());
                assertEquals(Collections.singleton(new ChunkPos((x - 8) >> 4, (z - 8) >> 4)), covering);
            }
        }
    }

    private WorldStructureGenerationData.StructureEntry partialEntry()
    {
        WorldStructureGenerationData.StructureEntry entry = WorldStructureGenerationData.StructureEntry.complete(
                "test-tower", "natural", new StructureBoundingBox(8, 60, 8, 39, 80, 23),
                AxisAlignedTransform2D.R1_F, false);
        entry.firstTime = false;
        entry.seed = 1234L;
        NBTTagCompound instance = new NBTTagCompound();
        instance.setInteger("marker", 73);
        entry.instanceData = instance;
        return entry;
    }
}
