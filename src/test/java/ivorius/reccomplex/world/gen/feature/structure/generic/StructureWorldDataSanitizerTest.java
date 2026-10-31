package ivorius.reccomplex.world.gen.feature.structure.generic;

import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.utils.FMLRemapper;
import ivorius.reccomplex.world.gen.feature.structure.registry.MCRegistrySpecial;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.datafix.FixTypes;
import net.minecraftforge.common.util.Constants;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static net.minecraft.nbt.CompressedStreamTools.readCompressed;
import static net.minecraft.nbt.CompressedStreamTools.writeCompressed;

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
    public void normalizesOnlyNestedLegacySpawnerEntityIds()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagList tileEntities = new NBTTagList();

        NBTTagCompound legacySpawner = tileEntity("MobSpawner", 0);
        legacySpawner.setShort("Delay", (short) 37);
        legacySpawner.setTag("SpawnData", spawnEntity("PigZombie", "legacy-spawn-data"));
        NBTTagList legacyPotentials = new NBTTagList();
        legacyPotentials.appendTag(spawnPotential("MinecartChest", "legacy-potential"));
        legacyPotentials.appendTag(spawnPotential("missingmod:custom_mob", "mod-potential"));
        legacySpawner.setTag("SpawnPotentials", legacyPotentials);
        tileEntities.appendTag(legacySpawner);

        NBTTagCompound modernSpawner = tileEntity("minecraft:mob_spawner", 1);
        modernSpawner.setShort("Delay", (short) 73);
        modernSpawner.setTag("SpawnData", spawnEntity("MinecartChest", "modern-spawn-data"));
        NBTTagList modernPotentials = new NBTTagList();
        modernPotentials.appendTag(spawnPotential("PigZombie", "modern-potential"));
        modernSpawner.setTag("SpawnPotentials", modernPotentials);
        tileEntities.appendTag(modernSpawner);

        NBTTagCompound unrelatedTileEntity = tileEntity("Trap", 2);
        unrelatedTileEntity.setTag("SpawnData", spawnEntity("PigZombie", "unrelated-spawn-data"));
        NBTTagList unrelatedPotentials = new NBTTagList();
        unrelatedPotentials.appendTag(spawnPotential("MinecartChest", "unrelated-potential"));
        unrelatedTileEntity.setTag("SpawnPotentials", unrelatedPotentials);
        tileEntities.appendTag(unrelatedTileEntity);

        worldData.setTag("tileEntities", tileEntities);
        NBTTagCompound original = worldData.copy();

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        NBTTagList sanitized = result.getWorldData().getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        Assert.assertEquals(3, sanitized.tagCount());

        NBTTagCompound sanitizedLegacy = sanitized.getCompoundTagAt(0);
        Assert.assertEquals("minecraft:mob_spawner", sanitizedLegacy.getString("id"));
        Assert.assertEquals("minecraft:zombie_pigman", sanitizedLegacy.getCompoundTag("SpawnData").getString("id"));
        Assert.assertEquals("legacy-spawn-data", sanitizedLegacy.getCompoundTag("SpawnData").getString("customNestedPayload"));
        Assert.assertEquals("minecraft:chest_minecart", sanitizedLegacy.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getCompoundTag("Entity").getString("id"));
        Assert.assertEquals("legacy-potential", sanitizedLegacy.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getCompoundTag("Entity").getString("customNestedPayload"));
        Assert.assertEquals("missingmod:custom_mob", sanitizedLegacy.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(1).getCompoundTag("Entity").getString("id"));
        Assert.assertEquals(37, sanitizedLegacy.getShort("Delay"));
        Assert.assertEquals("preserved-MobSpawner", sanitizedLegacy.getString("customPayload"));

        NBTTagCompound sanitizedModern = sanitized.getCompoundTagAt(1);
        Assert.assertEquals("minecraft:mob_spawner", sanitizedModern.getString("id"));
        Assert.assertEquals("minecraft:chest_minecart", sanitizedModern.getCompoundTag("SpawnData").getString("id"));
        Assert.assertEquals("modern-spawn-data", sanitizedModern.getCompoundTag("SpawnData").getString("customNestedPayload"));
        Assert.assertEquals("minecraft:zombie_pigman", sanitizedModern.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getCompoundTag("Entity").getString("id"));
        Assert.assertEquals("modern-potential", sanitizedModern.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getCompoundTag("Entity").getString("customNestedPayload"));
        Assert.assertEquals(73, sanitizedModern.getShort("Delay"));
        Assert.assertEquals("preserved-minecraft:mob_spawner", sanitizedModern.getString("customPayload"));

        NBTTagCompound sanitizedUnrelated = sanitized.getCompoundTagAt(2);
        Assert.assertEquals("minecraft:dispenser", sanitizedUnrelated.getString("id"));
        Assert.assertEquals("PigZombie", sanitizedUnrelated.getCompoundTag("SpawnData").getString("id"));
        Assert.assertEquals("MinecartChest", sanitizedUnrelated.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getCompoundTag("Entity").getString("id"));

        Assert.assertEquals(original, worldData);
        Assert.assertTrue(result.getMissingTileEntities().isEmpty());
        Assert.assertTrue(result.getMissingEntities().isEmpty());
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
    public void retainsRegisteredTileEntitiesWithoutConstructingThem()
    {
        String id = "reccomplex:sanitizer_constructor_test";
        ThrowingTileEntity.constructions = 0;
        TileEntity.register(id, ThrowingTileEntity.class);

        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagCompound originalTileEntity = tileEntity(id, 0);
        originalTileEntity.setString("malformedModPayload", "still-preserved");
        NBTTagList tileEntities = new NBTTagList();
        tileEntities.appendTag(originalTileEntity);
        worldData.setTag("tileEntities", tileEntities);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        NBTTagList sanitized = result.getWorldData().getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        Assert.assertEquals(1, sanitized.tagCount());
        Assert.assertEquals(id, sanitized.getCompoundTagAt(0).getString("id"));
        Assert.assertEquals("still-preserved", sanitized.getCompoundTagAt(0).getString("malformedModPayload"));
        Assert.assertEquals(0, ThrowingTileEntity.constructions);
        Assert.assertEquals(originalTileEntity,
                worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(0));
        Assert.assertTrue(result.getMissingTileEntities().isEmpty());
    }

    @Test
    public void recognizesCanonicalAndLegacyRcSpecialTileEntities()
    {
        MCRegistrySpecial previousRegistry = RecurrentComplex.specialRegistry;
        MCRegistrySpecial specialRegistry = new MCRegistrySpecial(null, new FMLRemapper());
        specialRegistry.register(new ResourceLocation("RCStructureGenerator"), ThrowingTileEntity.class);
        specialRegistry.register(new ResourceLocation("SGStructureGenerator"), ThrowingTileEntity.class);
        RecurrentComplex.specialRegistry = specialRegistry;
        ThrowingTileEntity.constructions = 0;

        try
        {
            NBTTagCompound worldData = new NBTTagCompound();
            NBTTagList tileEntities = new NBTTagList();
            tileEntities.appendTag(tileEntity("RCStructureGenerator", 0));
            tileEntities.appendTag(tileEntity("SGStructureGenerator", 1));
            worldData.setTag("tileEntities", tileEntities);

            StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
            Assert.assertNotNull(result);

            NBTTagList sanitized = result.getWorldData().getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
            Assert.assertEquals(2, sanitized.tagCount());
            Assert.assertEquals("RCStructureGenerator", sanitized.getCompoundTagAt(0).getString("id"));
            Assert.assertEquals("SGStructureGenerator", sanitized.getCompoundTagAt(1).getString("id"));
            Assert.assertEquals("preserved-RCStructureGenerator", sanitized.getCompoundTagAt(0).getString("customPayload"));
            Assert.assertEquals("preserved-SGStructureGenerator", sanitized.getCompoundTagAt(1).getString("customPayload"));
            Assert.assertEquals(0, ThrowingTileEntity.constructions);
            Assert.assertEquals("RCStructureGenerator",
                    worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(0).getString("id"));
            Assert.assertEquals("SGStructureGenerator",
                    worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(1).getString("id"));
            Assert.assertTrue(result.getMissingTileEntities().isEmpty());
        }
        finally
        {
            RecurrentComplex.specialRegistry = previousRegistry;
        }
    }

    @Test
    public void preservesTileEntitiesWhenVanillaRegistryAccessFails()
    {
        Assert.assertTrue(StructureWorldDataSanitizer.shouldKeepTileEntity(false, null));
        Assert.assertTrue(StructureWorldDataSanitizer.shouldKeepTileEntity(true, Boolean.FALSE));
        Assert.assertTrue(StructureWorldDataSanitizer.shouldKeepTileEntity(false, Boolean.TRUE));
        Assert.assertFalse(StructureWorldDataSanitizer.shouldKeepTileEntity(false, Boolean.FALSE));
    }

    @Test
    public void normalizesOfficialLegacyEntityIds()
    {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ItemFrame", "minecraft:item_frame");
        expected.put("ArmorStand", "minecraft:armor_stand");
        expected.put("MinecartChest", "minecraft:chest_minecart");
        expected.put("ZombieVillager", "minecraft:zombie_villager");

        expected.forEach((legacy, modern) -> {
            NBTTagCompound entity = entity(legacy, 0);
            Assert.assertTrue(StructureWorldDataSanitizer.normalizeLegacyEntityId(entity));
            Assert.assertEquals(modern, entity.getString("id"));
            Assert.assertEquals("preserved-" + legacy, entity.getString("customPayload"));
        });
    }

    @Test
    public void preservesLegacyEntityPayloadsDuringSanitization()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagList entities = new NBTTagList();

        NBTTagCompound itemFrame = entity("ItemFrame", 0);
        NBTTagCompound heldItem = new NBTTagCompound();
        heldItem.setString("id", "minecraft:diamond");
        heldItem.setByte("Count", (byte) 1);
        itemFrame.setTag("Item", heldItem);
        entities.appendTag(itemFrame);
        worldData.setTag("entities", entities);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        NBTTagList sanitized = result.getWorldData().getTagList("entities", Constants.NBT.TAG_COMPOUND);
        Assert.assertEquals(1, sanitized.tagCount());
        Assert.assertEquals("minecraft:item_frame", sanitized.getCompoundTagAt(0).getString("id"));
        Assert.assertEquals("minecraft:diamond", sanitized.getCompoundTagAt(0).getCompoundTag("Item").getString("id"));
        Assert.assertEquals("preserved-ItemFrame", sanitized.getCompoundTagAt(0).getString("customPayload"));

        Assert.assertEquals("ItemFrame", worldData.getTagList("entities", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getString("id"));
        Assert.assertTrue(result.getMissingEntities().isEmpty());
    }

    @Test
    public void removesAndRecordsUnknownEntities()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagList entities = new NBTTagList();
        entities.appendTag(entity("missingmod:missing_entity", 0));
        worldData.setTag("entities", entities);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.getWorldData().getTagList("entities", Constants.NBT.TAG_COMPOUND).tagCount());
        Assert.assertTrue(result.getMissingEntities().contains("missingmod:missing_entity"));
    }

    @Test
    public void preservesAllLootGenerationMarkers()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagCompound chest = tileEntity("minecraft:chest", 0);
        NBTTagList items = new NBTTagList();
        items.appendTag(modernLootMarker("minecraft:chests/simple_dungeon", 0));
        items.appendTag(legacyLootMarker("AbandonedChest", 1));
        items.appendTag(modernLootMarker("missing:loot_table", 2));
        items.appendTag(malformedLootMarker(3));
        chest.setTag("Items", items);

        NBTTagList tileEntities = new NBTTagList();
        tileEntities.appendTag(chest);
        worldData.setTag("tileEntities", tileEntities);

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        NBTTagList sanitizedItems = result.getWorldData()
                .getTagList("tileEntities", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0)
                .getTagList("Items", Constants.NBT.TAG_COMPOUND);
        Assert.assertEquals(4, sanitizedItems.tagCount());
        Assert.assertEquals(items, sanitizedItems);
        Assert.assertEquals(items, worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getTagList("Items", Constants.NBT.TAG_COMPOUND));
        Assert.assertTrue(result.getMissingLootTables().isEmpty());
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
    public void invalidatesCachesContainingResolvableLegacyEntities() throws Exception
    {
        Path legacyCache = temporaryFolder.newFile("legacy-entity.nbt").toPath();
        StructureWorldDataSanitizer.SanitizationResult legacyResult =
                new StructureWorldDataSanitizer.SanitizationResult(new NBTTagCompound());
        legacyResult.recordMissingEntity("ItemFrame");
        StructureWorldDataSanitizer.writeCache(legacyCache, "legacy-entity-hash", legacyResult);
        Assert.assertNull(StructureWorldDataSanitizer.readCache(legacyCache, "legacy-entity-hash"));

        Path missingCache = temporaryFolder.newFile("missing-entity.nbt").toPath();
        StructureWorldDataSanitizer.SanitizationResult missingResult =
                new StructureWorldDataSanitizer.SanitizationResult(new NBTTagCompound());
        missingResult.recordMissingEntity("missingmod:missing_entity");
        StructureWorldDataSanitizer.writeCache(missingCache, "missing-entity-hash", missingResult);
        Assert.assertNotNull(StructureWorldDataSanitizer.readCache(missingCache, "missing-entity-hash"));
    }

    @Test
    public void rejectsOldCacheVersionsAndHashMismatches() throws Exception
    {
        Path unversionedCache = temporaryFolder.newFile("unversioned.nbt").toPath();
        NBTTagCompound unversionedRoot = new NBTTagCompound();
        unversionedRoot.setString("sourceHash", "old-hash");
        unversionedRoot.setTag("worldData", new NBTTagCompound());
        try (OutputStream stream = Files.newOutputStream(unversionedCache))
        {
            writeCompressed(unversionedRoot, stream);
        }
        Assert.assertNull(StructureWorldDataSanitizer.readCache(unversionedCache, "old-hash"));

        Path versionOneCache = temporaryFolder.newFile("version-one.nbt").toPath();
        NBTTagCompound versionOneRoot = new NBTTagCompound();
        versionOneRoot.setInteger("cacheVersion", 1);
        versionOneRoot.setString("sourceHash", "version-one-hash");
        versionOneRoot.setTag("worldData", new NBTTagCompound());
        try (OutputStream stream = Files.newOutputStream(versionOneCache))
        {
            writeCompressed(versionOneRoot, stream);
        }
        Assert.assertNull(StructureWorldDataSanitizer.readCache(versionOneCache, "version-one-hash"));

        Path versionTwoCache = temporaryFolder.newFile("version-two.nbt").toPath();
        NBTTagCompound versionTwoRoot = new NBTTagCompound();
        versionTwoRoot.setInteger("cacheVersion", 2);
        versionTwoRoot.setString("sourceHash", "version-two-hash");
        versionTwoRoot.setTag("worldData", new NBTTagCompound());
        try (OutputStream stream = Files.newOutputStream(versionTwoCache))
        {
            writeCompressed(versionTwoRoot, stream);
        }
        Assert.assertNull(StructureWorldDataSanitizer.readCache(versionTwoCache, "version-two-hash"));

        Path currentCache = temporaryFolder.newFile("current.nbt").toPath();
        StructureWorldDataSanitizer.SanitizationResult currentResult =
                new StructureWorldDataSanitizer.SanitizationResult(new NBTTagCompound());
        StructureWorldDataSanitizer.writeCache(currentCache, "current-hash", currentResult);
        Assert.assertNotNull(StructureWorldDataSanitizer.readCache(currentCache, "current-hash"));
        Assert.assertNull(StructureWorldDataSanitizer.readCache(currentCache, "different-hash"));

        try (java.io.InputStream stream = Files.newInputStream(currentCache))
        {
            Assert.assertEquals(4, readCompressed(stream).getInteger("cacheVersion"));
        }
    }

    @Test
    public void bundledPyramidSpiderSpawnerSurvivesMinecraftLoadingAndClientUpdates() throws Exception
    {
        NBTTagCompound original = readBundledWorldData(
                "/assets/reccomplex/structures/active/structures/pyramid/PyramidMazeSpiderTurn.rcst");
        NBTTagCompound rawSpawner = original.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(0);
        Assert.assertEquals("CaveSpider", rawSpawner.getString("EntityId"));
        NBTTagCompound before = original.copy();
        NBTTagCompound sanitized = StructureWorldDataSanitizer.sanitize(original).getWorldData()
                .getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(0);
        Assert.assertEquals("minecraft:mob_spawner", sanitized.getString("id"));
        Assert.assertFalse(sanitized.hasKey("EntityId"));
        Assert.assertEquals("minecraft:cave_spider", sanitized.getCompoundTag("SpawnData").getString("id"));
        Assert.assertEquals(rawSpawner.getShort("MaxSpawnDelay"), sanitized.getShort("MaxSpawnDelay"));

        NBTTagCompound fixed = DataFixesManager.createFixer().process(FixTypes.BLOCK_ENTITY, sanitized.copy());
        TileEntityMobSpawner placed = new TileEntityMobSpawner();
        placed.readFromNBT(fixed);
        Assert.assertEquals("minecraft:cave_spider", placed.writeToNBT(new NBTTagCompound()).getCompoundTag("SpawnData").getString("id"));
        TileEntityMobSpawner client = new TileEntityMobSpawner();
        client.readFromNBT(placed.getUpdateTag());
        Assert.assertEquals("minecraft:cave_spider", client.writeToNBT(new NBTTagCompound()).getCompoundTag("SpawnData").getString("id"));
        Assert.assertEquals(before, original);
    }

    @Test
    public void convertsLegacySpawnerSchemaForBothLegacyAndModernTileIds()
    {
        for (String tileID : new String[]{"MobSpawner", "minecraft:mob_spawner"})
        for (boolean hasEntityId : new boolean[]{true, false})
        {
            NBTTagCompound spawner = tileEntity(tileID, 3);
            if (hasEntityId) spawner.setString("EntityId", "CaveSpider");
            spawner.setShort("Delay", (short) 47);
            spawner.setShort("SpawnCount", (short) 6);
            NBTTagCompound spawnData = new NBTTagCompound();
            spawnData.setString("id", "minecraft:skeleton");
            spawnData.setString("customNestedPayload", "keep-spawn-data");
            spawner.setTag("SpawnData", spawnData);
            NBTTagList potentials = new NBTTagList();
            NBTTagCompound legacy = new NBTTagCompound();
            legacy.setString("Type", "PigZombie");
            legacy.setInteger("Weight", 7);
            NBTTagCompound properties = new NBTTagCompound();
            properties.setString("customNestedPayload", "keep-legacy-properties");
            legacy.setTag("Properties", properties);
            potentials.appendTag(legacy);
            NBTTagCompound moddedLegacy = new NBTTagCompound();
            moddedLegacy.setString("Type", "missingmod:custom_mob");
            moddedLegacy.setInteger("Weight", 11);
            NBTTagCompound moddedProperties = new NBTTagCompound();
            moddedProperties.setString("customNestedPayload", "keep-modded-properties");
            moddedLegacy.setTag("Properties", moddedProperties);
            potentials.appendTag(moddedLegacy);
            NBTTagCompound modernPotential = spawnPotential("minecraft:skeleton", "keep-modern-potential");
            potentials.appendTag(modernPotential);
            spawner.setTag("SpawnPotentials", potentials);
            NBTTagCompound worldData = new NBTTagCompound();
            NBTTagList tiles = new NBTTagList(); tiles.appendTag(spawner); worldData.setTag("tileEntities", tiles);
            NBTTagCompound original = worldData.copy();

            NBTTagCompound result = StructureWorldDataSanitizer.sanitize(worldData).getWorldData()
                    .getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(0);
            Assert.assertEquals("minecraft:mob_spawner", result.getString("id"));
            Assert.assertFalse(result.hasKey("EntityId"));
            Assert.assertEquals(hasEntityId ? "minecraft:cave_spider" : "minecraft:skeleton", result.getCompoundTag("SpawnData").getString("id"));
            Assert.assertEquals("keep-spawn-data", result.getCompoundTag("SpawnData").getString("customNestedPayload"));
            NBTTagList converted = result.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND);
            Assert.assertEquals(7, converted.getCompoundTagAt(0).getInteger("Weight"));
            Assert.assertEquals("minecraft:zombie_pigman", converted.getCompoundTagAt(0).getCompoundTag("Entity").getString("id"));
            Assert.assertEquals("keep-legacy-properties", converted.getCompoundTagAt(0).getCompoundTag("Entity").getString("customNestedPayload"));
            Assert.assertFalse(converted.getCompoundTagAt(0).hasKey("Type"));
            Assert.assertFalse(converted.getCompoundTagAt(0).hasKey("Properties"));
            Assert.assertEquals(11, converted.getCompoundTagAt(1).getInteger("Weight"));
            Assert.assertEquals("missingmod:custom_mob", converted.getCompoundTagAt(1).getCompoundTag("Entity").getString("id"));
            Assert.assertEquals("keep-modded-properties", converted.getCompoundTagAt(1).getCompoundTag("Entity").getString("customNestedPayload"));
            Assert.assertEquals(modernPotential, converted.getCompoundTagAt(2));
            Assert.assertEquals(47, result.getShort("Delay"));
            Assert.assertEquals(6, result.getShort("SpawnCount"));
            Assert.assertEquals(spawner.getString("customPayload"), result.getString("customPayload"));
            Assert.assertEquals(original, worldData);
        }
    }

    @Test
    public void leavesModernCustomSpawnersAndUnrelatedLegacyLookingFieldsUnchanged()
    {
        NBTTagCompound worldData = new NBTTagCompound();
        NBTTagList tiles = new NBTTagList();
        NBTTagCompound modern = tileEntity("minecraft:mob_spawner", 0);
        modern.setTag("SpawnData", spawnEntity("missingmod:custom_mob", "modern-data"));
        NBTTagList potentials = new NBTTagList(); potentials.appendTag(spawnPotential("minecraft:cave_spider", "modern-potential"));
        modern.setTag("SpawnPotentials", potentials); tiles.appendTag(modern);
        NBTTagCompound unrelated = tileEntity("minecraft:dispenser", 1);
        unrelated.setString("EntityId", "CaveSpider");
        NBTTagList unrelatedPotentials = new NBTTagList();
        NBTTagCompound unrelatedEntry = new NBTTagCompound();
        unrelatedEntry.setString("Type", "PigZombie"); unrelatedEntry.setTag("Properties", new NBTTagCompound());
        unrelatedPotentials.appendTag(unrelatedEntry); unrelated.setTag("SpawnPotentials", unrelatedPotentials);
        tiles.appendTag(unrelated); worldData.setTag("tileEntities", tiles);
        Assert.assertEquals(worldData, StructureWorldDataSanitizer.sanitize(worldData).getWorldData());
    }

    @Test
    public void rejectsVersionThreeSpawnerCacheAndRoundTripsRepairedCache() throws Exception
    {
        NBTTagCompound original = readBundledWorldData(
                "/assets/reccomplex/structures/active/structures/pyramid/PyramidMazeSpiderTurn.rcst");
        NBTTagCompound stale = original.copy();
        stale.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND).getCompoundTagAt(0).setString("id", "minecraft:mob_spawner");
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("cacheVersion", 3); root.setString("sourceHash", "same-source"); root.setTag("worldData", stale);
        Path path = temporaryFolder.newFile("version-three-spawner.nbt").toPath();
        try (OutputStream stream = Files.newOutputStream(path)) { writeCompressed(root, stream); }
        Assert.assertNull(StructureWorldDataSanitizer.readCache(path, "same-source"));
        StructureWorldDataSanitizer.writeCache(path, "same-source", StructureWorldDataSanitizer.sanitize(original));
        NBTTagCompound cached = StructureWorldDataSanitizer.readCache(path, "same-source");
        Assert.assertNotNull(cached);
        Assert.assertEquals("minecraft:cave_spider", cached.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND)
                .getCompoundTagAt(0).getCompoundTag("SpawnData").getString("id"));
    }

    @Test
    public void bundledSmallFortRuinsRetainsAllLootMarkers() throws Exception
    {
        NBTTagCompound worldData = readBundledWorldData(
                "/assets/reccomplex/structures/active/structures/overworld/SmallFortRuins.rcst");
        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        int markers = 0;
        int legacyAbandonedChestMarkers = 0;
        NBTTagList tileEntities = result.getWorldData().getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tileEntities.tagCount(); i++)
        {
            NBTTagList items = tileEntities.getCompoundTagAt(i).getTagList("Items", Constants.NBT.TAG_COMPOUND);
            for (int j = 0; j < items.tagCount(); j++)
            {
                NBTTagCompound item = items.getCompoundTagAt(j);
                if (!"reccomplex:inventory_generation_tag".equals(item.getString("id")))
                    continue;

                markers++;
                NBTTagCompound display = item.getCompoundTag("tag").getCompoundTag("display");
                if ("AbandonedChest".equals(display.getString("Name")))
                    legacyAbandonedChestMarkers++;
            }
        }

        Assert.assertEquals(14, markers);
        Assert.assertEquals(13, legacyAbandonedChestMarkers);
    }

    @Test
    public void bundledVeldtBathRetainsAndModernizesItemFrames() throws Exception
    {
        NBTTagCompound worldData = readBundledWorldData(
                "/assets/reccomplex/structures/active/structures/overworld/VeldtBath.rcst");
        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        List<NBTTagCompound> originalFrames = entitiesWithId(worldData, "ItemFrame");
        List<NBTTagCompound> sanitizedFrames = entitiesWithId(result.getWorldData(), "minecraft:item_frame");
        Assert.assertEquals(8, originalFrames.size());
        Assert.assertEquals(8, sanitizedFrames.size());

        for (int i = 0; i < originalFrames.size(); i++)
        {
            Assert.assertEquals(originalFrames.get(i).getCompoundTag("Item"),
                    sanitizedFrames.get(i).getCompoundTag("Item"));
            Assert.assertEquals(originalFrames.get(i).getTag("Pos"), sanitizedFrames.get(i).getTag("Pos"));
        }
    }

    @Test
    public void bundledOldWatchtowerHasModernSpawnerIdsAndNoOrphanDoorTileEntity() throws Exception
    {
        NBTTagCompound worldData = readBundledWorldData(
                "/assets/reccomplex/structures/active/structures/overworld/OldWatchtower.rcst");
        NBTTagList tileEntities = worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < tileEntities.tagCount(); i++)
            Assert.assertNotEquals("doorTileEntity", tileEntities.getCompoundTagAt(i).getString("id"));

        StructureWorldDataSanitizer.SanitizationResult result = StructureWorldDataSanitizer.sanitize(worldData);
        Assert.assertNotNull(result);

        List<String> spawnDataIds = new ArrayList<>();
        List<String> potentialIds = new ArrayList<>();
        NBTTagList sanitized = result.getWorldData().getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < sanitized.tagCount(); i++)
        {
            NBTTagCompound tileEntity = sanitized.getCompoundTagAt(i);
            if (!"minecraft:mob_spawner".equals(tileEntity.getString("id")))
                continue;

            spawnDataIds.add(tileEntity.getCompoundTag("SpawnData").getString("id"));
            NBTTagList potentials = tileEntity.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND);
            for (int j = 0; j < potentials.tagCount(); j++)
                potentialIds.add(potentials.getCompoundTagAt(j).getCompoundTag("Entity").getString("id"));
        }

        Assert.assertEquals(2, spawnDataIds.size());
        Assert.assertTrue(spawnDataIds.contains("minecraft:zombie"));
        Assert.assertTrue(spawnDataIds.contains("minecraft:skeleton"));
        Assert.assertEquals(2, potentialIds.size());
        Assert.assertTrue(potentialIds.contains("minecraft:zombie"));
        Assert.assertTrue(potentialIds.contains("minecraft:skeleton"));
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

    private static NBTTagCompound entity(String id, int x)
    {
        NBTTagCompound entity = new NBTTagCompound();
        entity.setString("id", id);
        entity.setInteger("x", x);
        entity.setString("customPayload", "preserved-" + id);
        return entity;
    }

    private static NBTTagCompound spawnEntity(String id, String customPayload)
    {
        NBTTagCompound entity = new NBTTagCompound();
        entity.setString("id", id);
        entity.setString("customNestedPayload", customPayload);
        return entity;
    }

    private static NBTTagCompound spawnPotential(String id, String customPayload)
    {
        NBTTagCompound potential = new NBTTagCompound();
        potential.setInteger("Weight", 1);
        potential.setTag("Entity", spawnEntity(id, customPayload));
        return potential;
    }

    private static NBTTagCompound modernLootMarker(String key, int slot)
    {
        NBTTagCompound item = lootMarker(slot);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("itemCollectionKey", key);
        item.setTag("tag", tag);
        return item;
    }

    private static NBTTagCompound legacyLootMarker(String key, int slot)
    {
        NBTTagCompound item = lootMarker(slot);
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", key);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("display", display);
        item.setTag("tag", tag);
        return item;
    }

    private static NBTTagCompound malformedLootMarker(int slot)
    {
        return lootMarker(slot);
    }

    private static NBTTagCompound lootMarker(int slot)
    {
        NBTTagCompound item = new NBTTagCompound();
        item.setByte("Slot", (byte) slot);
        item.setString("id", "reccomplex:inventory_generation_tag");
        item.setByte("Count", (byte) 1);
        item.setShort("Damage", (short) 0);
        return item;
    }

    private static List<NBTTagCompound> entitiesWithId(NBTTagCompound worldData, String id)
    {
        List<NBTTagCompound> matches = new ArrayList<>();
        NBTTagList entities = worldData.getTagList("entities", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < entities.tagCount(); i++)
        {
            NBTTagCompound entity = entities.getCompoundTagAt(i);
            if (id.equals(entity.getString("id")))
                matches.add(entity);
        }
        return matches;
    }

    private static NBTTagCompound readBundledWorldData(String resource) throws Exception
    {
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
        return readCompressed(new ByteArrayInputStream(worldDataBytes));
    }

    public static class ThrowingTileEntity extends TileEntity
    {
        static int constructions;

        public ThrowingTileEntity()
        {
            constructions++;
            throw new IllegalStateException("Sanitization must not construct registered tile entities");
        }
    }
}
