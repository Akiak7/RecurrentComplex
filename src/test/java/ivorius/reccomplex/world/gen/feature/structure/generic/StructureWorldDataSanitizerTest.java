package ivorius.reccomplex.world.gen.feature.structure.generic;

import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static net.minecraft.nbt.CompressedStreamTools.readCompressed;

public class StructureWorldDataSanitizerTest
{
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void bootstrapMinecraft()
    {
        Bootstrap.register();
    }

    @Test
    public void preservesRcInternalPlaceholderBlocksInCompoundMapping()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagCompound blockCollection = new NBTTagCompound();
        NBTTagCompound mapping = new NBTTagCompound();

        NBTTagCompound placeholder = new NBTTagCompound();
        placeholder.setString("block", "reccomplex:generic_space");

        NBTTagCompound properties = new NBTTagCompound();
        properties.setString("facing", "north");
        placeholder.setTag("properties", properties);
        placeholder.setInteger("meta", 3);
        mapping.setTag("0", placeholder);

        NBTTagCompound missing = new NBTTagCompound();
        missing.setString("block", "example:missing_block");
        missing.setTag("properties", new NBTTagCompound());
        mapping.setTag("1", missing);

        blockCollection.setTag("mapping", mapping);
        worldData.setTag("blockCollection", blockCollection);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        NBTTagCompound sanitizedMapping = result.getWorldData()
                .getCompoundTag("blockCollection")
                .getCompoundTag("mapping");

        NBTTagCompound sanitizedPlaceholder = sanitizedMapping.getCompoundTag("0");
        Assert.assertEquals("reccomplex:generic_space", sanitizedPlaceholder.getString("block"));
        Assert.assertTrue(sanitizedPlaceholder.hasKey("properties", Constants.NBT.TAG_COMPOUND));
        Assert.assertEquals("north", sanitizedPlaceholder.getCompoundTag("properties").getString("facing"));
        Assert.assertEquals(3, sanitizedPlaceholder.getInteger("meta"));

        NBTTagCompound sanitizedMissing = sanitizedMapping.getCompoundTag("1");
        Assert.assertEquals("minecraft:air", sanitizedMissing.getString("block"));
        Assert.assertFalse(sanitizedMissing.hasKey("properties", Constants.NBT.TAG_COMPOUND));
    }

    @Test
    public void preservesLegacyRcInternalAliasesInListMapping()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagCompound blockCollection = new NBTTagCompound();
        NBTTagList mapping = new NBTTagList();
        mapping.appendTag(new NBTTagString("negativeSpace"));
        mapping.appendTag(new NBTTagString("reccomplex:naturalFloor"));
        mapping.appendTag(new NBTTagString("reccomplex:spawnCommand"));
        mapping.appendTag(new NBTTagString("missing:block"));

        blockCollection.setTag("mapping", mapping);
        worldData.setTag("blockCollection", blockCollection);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        NBTTagList sanitized = result.getWorldData().getCompoundTag("blockCollection").getTagList("mapping", Constants.NBT.TAG_STRING);
        Assert.assertEquals("negativeSpace", sanitized.getStringTagAt(0));
        Assert.assertEquals("reccomplex:naturalFloor", sanitized.getStringTagAt(1));
        Assert.assertEquals("reccomplex:spawnCommand", sanitized.getStringTagAt(2));
        Assert.assertEquals("minecraft:air", sanitized.getStringTagAt(3));
    }

    @Test
    public void normalizesOfficialLegacyTileEntityIds()
    {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MobSpawner", "minecraft:mob_spawner");
        expected.put("FlowerPot", "minecraft:flower_pot");
        expected.put("Trap", "minecraft:dispenser");
        expected.put("RecordPlayer", "minecraft:jukebox");
        expected.put("Cauldron", "minecraft:brewing_stand");

        expected.forEach((legacy, modern) -> {
            NBTTagCompound tileEntity = tileEntity(legacy, 0);
            Assert.assertTrue(StructureWorldDataSanitizer.normalizeLegacyTileEntityId(tileEntity));
            Assert.assertEquals(modern, tileEntity.getString("id"));
            Assert.assertEquals("preserved-" + legacy, tileEntity.getString("customPayload"));
        });
    }

    @Test
    public void preservesLegacyTileEntityPayloadsDuringSanitization()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagList tileEntities = new NBTTagList();

        NBTTagCompound spawner = tileEntity("MobSpawner", 0);
        NBTTagCompound spawnData = new NBTTagCompound();
        spawnData.setString("id", "minecraft:zombie");
        spawner.setTag("SpawnData", spawnData);
        tileEntities.appendTag(spawner);

        NBTTagCompound dispenser = tileEntity("Trap", 1);
        NBTTagList items = new NBTTagList();
        NBTTagCompound item = new NBTTagCompound();
        item.setByte("Slot", (byte) 0);
        item.setString("id", "minecraft:stone");
        item.setByte("Count", (byte) 1);
        item.setShort("Damage", (short) 0);
        items.appendTag(item);
        dispenser.setTag("Items", items);
        tileEntities.appendTag(dispenser);

        worldData.setTag("tileEntities", tileEntities);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        NBTTagList sanitized = result.getWorldData().getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        Assert.assertEquals(2, sanitized.tagCount());
        Assert.assertEquals("minecraft:mob_spawner", sanitized.getCompoundTagAt(0).getString("id"));
        Assert.assertEquals("minecraft:zombie", sanitized.getCompoundTagAt(0).getCompoundTag("SpawnData").getString("id"));
        Assert.assertEquals("preserved-MobSpawner", sanitized.getCompoundTagAt(0).getString("customPayload"));
        Assert.assertEquals("minecraft:dispenser", sanitized.getCompoundTagAt(1).getString("id"));
        Assert.assertEquals(1, sanitized.getCompoundTagAt(1).getTagList("Items", Constants.NBT.TAG_COMPOUND).tagCount());
        Assert.assertEquals("preserved-Trap", sanitized.getCompoundTagAt(1).getString("customPayload"));

        Assert.assertEquals("MobSpawner", worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(0).getString("id"));
        Assert.assertEquals("Trap", worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(1).getString("id"));
        Assert.assertTrue(result.getMissingTileEntities().isEmpty());
    }

    @Test
    public void removesAndRecordsUnknownTileEntities()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagList tileEntities = new NBTTagList();
        tileEntities.appendTag(tileEntity("missingmod:missing_machine", 0));
        worldData.setTag("tileEntities", tileEntities);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.getWorldData().getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).tagCount());
        Assert.assertTrue(result.getMissingTileEntities().contains("missingmod:missing_machine"));
    }

    @Test
    public void invalidatesCachesContainingResolvableLegacyTileEntities() throws Exception
    {
        Path legacyCache = temporaryFolder.newFile("legacy.nbt").toPath();
        StructureWorldDataSanitizer.SanitizationResult legacyResult =
                new StructureWorldDataSanitizer.SanitizationResult(new NBTTagCompound());
        legacyResult.recordMissingTileEntity("MobSpawner");
        StructureWorldDataSanitizer.writeCache(legacyCache, "legacy-hash", legacyResult);
        Assert.assertNull(StructureWorldDataSanitizer.readCache(legacyCache, "legacy-hash"));

        Path missingCache = temporaryFolder.newFile("missing.nbt").toPath();
        StructureWorldDataSanitizer.SanitizationResult missingResult =
                new StructureWorldDataSanitizer.SanitizationResult(new NBTTagCompound());
        missingResult.recordMissingTileEntity("missingmod:missing_machine");
        StructureWorldDataSanitizer.writeCache(missingCache, "missing-hash", missingResult);
        Assert.assertNotNull(StructureWorldDataSanitizer.readCache(missingCache, "missing-hash"));
    }

    @Test
    public void bundledOldWatchtowerHasNoOrphanDoorTileEntity() throws Exception
    {
        String resource = "/assets/reccomplex/structures/active/structures/overworld/OldWatchtower.rcst";
        byte[] worldDataBytes = null;

        try (ZipInputStream zip = new ZipInputStream(StructureWorldDataSanitizerTest.class.getResourceAsStream(resource)))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                if (StructureSaveHandler.WORLD_DATA_NBT_FILENAME.equals(entry.getName()))
                {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = zip.read(buffer)) >= 0)
                        output.write(buffer, 0, read);
                    worldDataBytes = output.toByteArray();
                    break;
                }
            }
        }

        Assert.assertNotNull(worldDataBytes);
        NBTTagCompound worldData = readCompressed(new ByteArrayInputStream(worldDataBytes));
        NBTTagList tileEntities = worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tileEntities.tagCount(); i++)
            Assert.assertNotEquals("doorTileEntity", tileEntities.getCompoundTagAt(i).getString("id"));
    }

    private static NBTTagCompound tileEntity(String id, int x)
    {
        NBTTagCompound tileEntity = new NBTTagCompound();
        tileEntity.setString("id", id);
        tileEntity.setInteger("x", x);
        tileEntity.setInteger("y", 0);
        tileEntity.setInteger("z", 0);
        tileEntity.setString("customPayload", "preserved-" + id);
        return tileEntity;
    }
}
