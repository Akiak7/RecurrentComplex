package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.common.hash.Hashing;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.utils.accessor.RCAccessorTileEntity;
import ivorius.reccomplex.world.storage.loot.WeightedItemCollectionRegistry;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.fixes.EntityId;
import net.minecraft.util.datafix.fixes.TileEntityId;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.nbt.CompressedStreamTools.readCompressed;
import static net.minecraft.nbt.CompressedStreamTools.writeCompressed;

/**
 * Sanitizes structure world data so that missing content is removed before generation.
 */
public class StructureWorldDataSanitizer
{
    private static final String CACHE_VERSION_TAG = "cacheVersion";
    private static final int CACHE_VERSION = 3;
    private static final String AIR_BLOCK_ID = "minecraft:air";
    private static final String MOB_SPAWNER_TILE_ENTITY_ID = "minecraft:mob_spawner";
    private static final Set<String> RC_INTERNAL_BLOCK_IDS = new HashSet<>();
    private static final TileEntityId LEGACY_TILE_ENTITY_ID_FIXER = new TileEntityId();
    private static final EntityId LEGACY_ENTITY_ID_FIXER = new EntityId();
    private static final AtomicBoolean TILE_ENTITY_REGISTRY_WARNING_LOGGED = new AtomicBoolean();

    static
    {
        registerRcInternalBlock("generic_space", "negativeSpace");
        registerRcInternalBlock("generic_solid", "naturalFloor");
        registerRcInternalBlock("spawn_script", "spawnCommand", "weighted_command_block", "spawn_command");
    }

    private StructureWorldDataSanitizer()
    {
    }

    @Nullable
    public static SanitizationResult sanitize(@Nullable NBTTagCompound original)
    {
        if (original == null)
            return null;

        NBTTagCompound sanitized = original.copy();
        SanitizationResult result = new SanitizationResult(sanitized);

        sanitizeBlockMapping(result);
        sanitizeTileEntities(result);
        sanitizeEntities(result);

        return result;
    }

    private static void sanitizeBlockMapping(SanitizationResult result)
    {
        NBTTagCompound worldData = result.worldData;
        if (!worldData.hasKey("blockCollection", Constants.NBT.TAG_COMPOUND))
            return;

        NBTTagCompound blockCollection = worldData.getCompoundTag("blockCollection");
        String airName = AIR_BLOCK_ID;

        if (blockCollection.hasKey("mapping", Constants.NBT.TAG_LIST))
        {
            NBTTagList mapping = blockCollection.getTagList("mapping", Constants.NBT.TAG_STRING);
            for (int i = 0; i < mapping.tagCount(); i++)
            {
                String blockId = mapping.getStringTagAt(i);
                if (!isKnownBlock(blockId))
                {
                    result.recordMissingBlock(blockId);
                    mapping.set(i, new NBTTagString(airName));
                    debugBlockSanitized("list[" + i + "]", blockId, airName);
                }
            }
        }
        else if (blockCollection.hasKey("mapping", Constants.NBT.TAG_COMPOUND))
        {
            NBTTagCompound mapping = blockCollection.getCompoundTag("mapping");
            Set<String> keys = new HashSet<>(mapping.getKeySet());
            for (String key : keys)
            {
                byte type = mapping.getTagId(key);
                if (type == Constants.NBT.TAG_STRING)
                {
                    String blockId = mapping.getString(key);
                    if (!isKnownBlock(blockId))
                    {
                        result.recordMissingBlock(blockId);
                        mapping.setString(key, airName);
                        debugBlockSanitized("mapping." + key, blockId, airName);
                    }
                }
                else if (type == Constants.NBT.TAG_COMPOUND)
                {
                    NBTTagCompound entry = mapping.getCompoundTag(key);
                    String blockId = entry.hasKey("block", Constants.NBT.TAG_STRING)
                            ? entry.getString("block")
                            : entry.getString("id");
                    if (!isKnownBlock(blockId))
                    {
                        result.recordMissingBlock(blockId);
                        entry.setString("block", airName);
                        if (entry.hasKey("id", Constants.NBT.TAG_STRING))
                            entry.setString("id", airName);
                        entry.removeTag("properties");
                        debugBlockSanitized("mapping." + key, blockId, airName);
                    }
                }
            }
        }
    }

    private static boolean isKnownBlock(@Nullable String blockId)
    {
        if (blockId == null || blockId.isEmpty())
            return false;

        if (isRcInternalBlockId(blockId))
            return true;

        try
        {
            ResourceLocation location = new ResourceLocation(blockId);
            return ForgeRegistries.BLOCKS.containsKey(location);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static void registerRcInternalBlock(String canonical, String... aliases)
    {
        RC_INTERNAL_BLOCK_IDS.add(canonical.toLowerCase(Locale.ROOT));
        RC_INTERNAL_BLOCK_IDS.add((RecurrentComplex.MOD_ID + ":" + canonical).toLowerCase(Locale.ROOT));

        for (String alias : aliases)
        {
            RC_INTERNAL_BLOCK_IDS.add(alias.toLowerCase(Locale.ROOT));
            RC_INTERNAL_BLOCK_IDS.add((RecurrentComplex.MOD_ID + ":" + alias).toLowerCase(Locale.ROOT));
        }
    }

    private static boolean isRcInternalBlockId(String blockId)
    {
        return RC_INTERNAL_BLOCK_IDS.contains(blockId.toLowerCase(Locale.ROOT));
    }

    private static void debugBlockSanitized(String source, @Nullable String previousBlockId, String replacement)
    {
        if (RecurrentComplex.logger != null)
            RecurrentComplex.logger.debug("Sanitized structure block mapping {} from '{}' to '{}'", source, previousBlockId, replacement);
    }

    private static void sanitizeTileEntities(SanitizationResult result)
    {
        NBTTagCompound worldData = result.worldData;
        if (!worldData.hasKey("tileEntities", Constants.NBT.TAG_LIST))
            return;

        NBTTagList tileEntities = worldData.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        NBTTagList sanitized = new NBTTagList();
        boolean changed = false;

        for (int i = 0; i < tileEntities.tagCount(); i++)
        {
            NBTTagCompound tileEntity = tileEntities.getCompoundTagAt(i);
            if (normalizeLegacyTileEntityId(tileEntity))
                changed = true;
            if (normalizeLegacySpawnerEntityIds(tileEntity))
                changed = true;
            boolean keep = isKnownTileEntity(tileEntity.getString("id"));

            if (keep)
            {
                sanitized.appendTag(tileEntity);
            }
            else
            {
                String id = tileEntity.getString("id");
                if (!isKnownTileEntity(id))
                    result.recordMissingTileEntity(id);
                changed = true;
            }
        }

        if (changed)
            worldData.setTag("tileEntities", sanitized);
    }

    private static void sanitizeEntities(SanitizationResult result)
    {
        NBTTagCompound worldData = result.worldData;
        if (!worldData.hasKey("entities", Constants.NBT.TAG_LIST))
            return;

        NBTTagList entities = worldData.getTagList("entities", Constants.NBT.TAG_COMPOUND);
        NBTTagList sanitized = new NBTTagList();
        boolean changed = false;

        for (int i = 0; i < entities.tagCount(); i++)
        {
            NBTTagCompound entity = entities.getCompoundTagAt(i);
            if (normalizeLegacyEntityId(entity))
                changed = true;

            if (isKnownEntity(entity.getString("id")))
            {
                sanitized.appendTag(entity);
            }
            else
            {
                result.recordMissingEntity(entity.getString("id"));
                changed = true;
            }
        }

        if (changed)
            worldData.setTag("entities", sanitized);
    }

    private static boolean isKnownEntity(@Nullable String entityId)
    {
        if (entityId == null || entityId.isEmpty())
            return false;

        try
        {
            ResourceLocation location = new ResourceLocation(entityId);
            return ForgeRegistries.ENTITIES.containsKey(location);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    public static String computeHash(byte[] bytes)
    {
        return Hashing.sha1().hashBytes(bytes).toString();
    }

    @Nullable
    public static NBTTagCompound readCache(Path path, String expectedHash) throws IOException
    {
        if (!Files.exists(path))
            return null;

        try (InputStream stream = Files.newInputStream(path))
        {
            NBTTagCompound root = readCompressed(stream);
            if (root == null)
                return null;

            if (!root.hasKey(CACHE_VERSION_TAG, Constants.NBT.TAG_INT)
                    || root.getInteger(CACHE_VERSION_TAG) != CACHE_VERSION)
                return null;

            if (!root.hasKey("sourceHash", Constants.NBT.TAG_STRING))
                return null;

            String stored = root.getString("sourceHash");
            if (!expectedHash.equals(stored))
                return null;

            if (!root.hasKey("worldData", Constants.NBT.TAG_COMPOUND))
                return null;

            if (cacheShouldBeInvalidated(root))
                return null;

            return root.getCompoundTag("worldData");
        }
    }

    public static void writeCache(Path path, String hash, SanitizationResult result) throws IOException
    {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger(CACHE_VERSION_TAG, CACHE_VERSION);
        root.setString("sourceHash", hash);
        root.setTag("worldData", result.worldData);
        writeStringSet(root, "missingBlocks", result.missingBlocks);
        writeStringSet(root, "missingTileEntities", result.missingTileEntities);
        writeStringSet(root, "missingEntities", result.missingEntities);
        writeStringSet(root, "missingLootTables", result.missingLootTables);

        if (path.getParent() != null)
            Files.createDirectories(path.getParent());

        try (OutputStream stream = Files.newOutputStream(path))
        {
            writeCompressed(root, stream);
        }
    }

    private static void writeStringSet(NBTTagCompound root, String key, Collection<String> entries)
    {
        if (entries == null || entries.isEmpty())
            return;

        NBTTagList list = new NBTTagList();
        for (String entry : entries)
        {
            if (entry == null)
                continue;
            list.appendTag(new NBTTagString(entry));
        }

        if (list.tagCount() > 0)
            root.setTag(key, list);
    }

    private static boolean cacheShouldBeInvalidated(NBTTagCompound root)
    {
        if (hasResolvedBlocks(root))
            return true;
        if (hasResolvedTileEntities(root))
            return true;
        if (hasResolvedEntities(root))
            return true;
        return hasResolvedLootTables(root);
    }

    private static boolean hasResolvedBlocks(NBTTagCompound root)
    {
        if (!root.hasKey("missingBlocks", Constants.NBT.TAG_LIST))
            return false;

        NBTTagList list = root.getTagList("missingBlocks", Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++)
        {
            String id = list.getStringTagAt(i);
            if (isKnownBlock(id))
                return true;
        }

        return false;
    }

    private static boolean hasResolvedTileEntities(NBTTagCompound root)
    {
        if (!root.hasKey("missingTileEntities", Constants.NBT.TAG_LIST))
            return false;

        NBTTagList list = root.getTagList("missingTileEntities", Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++)
        {
            String id = list.getStringTagAt(i);
            if (isKnownTileEntity(id))
                return true;
        }

        return false;
    }

    static boolean normalizeLegacyTileEntityId(NBTTagCompound tileEntity)
    {
        String previousId = tileEntity.getString("id");
        LEGACY_TILE_ENTITY_ID_FIXER.fixTagCompound(tileEntity);
        return !previousId.equals(tileEntity.getString("id"));
    }

    static boolean normalizeLegacyEntityId(NBTTagCompound entity)
    {
        String previousId = entity.getString("id");
        LEGACY_ENTITY_ID_FIXER.fixTagCompound(entity);
        return !previousId.equals(entity.getString("id"));
    }

    static boolean normalizeLegacySpawnerEntityIds(NBTTagCompound tileEntity)
    {
        if (!MOB_SPAWNER_TILE_ENTITY_ID.equals(tileEntity.getString("id")))
            return false;

        boolean changed = false;

        if (tileEntity.hasKey("SpawnData", Constants.NBT.TAG_COMPOUND))
            changed |= normalizeLegacyEntityId(tileEntity.getCompoundTag("SpawnData"));

        if (tileEntity.hasKey("SpawnPotentials", Constants.NBT.TAG_LIST))
        {
            NBTTagList potentials = tileEntity.getTagList("SpawnPotentials", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < potentials.tagCount(); i++)
            {
                NBTTagCompound potential = potentials.getCompoundTagAt(i);
                if (potential.hasKey("Entity", Constants.NBT.TAG_COMPOUND))
                    changed |= normalizeLegacyEntityId(potential.getCompoundTag("Entity"));
            }
        }

        return changed;
    }

    private static boolean isKnownTileEntity(@Nullable String tileEntityId)
    {
        if (tileEntityId == null || tileEntityId.isEmpty())
            return false;

        try
        {
            NBTTagCompound stub = new NBTTagCompound();
            stub.setString("id", tileEntityId);
            normalizeLegacyTileEntityId(stub);
            ResourceLocation id = new ResourceLocation(stub.getString("id"));

            boolean specialRegistered = RecurrentComplex.specialRegistry != null
                    && RecurrentComplex.specialRegistry.hasTileEntity(id);
            Boolean vanillaRegistered = specialRegistered ? null : RCAccessorTileEntity.isRegistered(id);

            if (!specialRegistered && vanillaRegistered == null
                    && RecurrentComplex.logger != null
                    && TILE_ENTITY_REGISTRY_WARNING_LOGGED.compareAndSet(false, true))
            {
                RecurrentComplex.logger.warn("Unable to inspect the tile entity registry; preserving tile entity NBT conservatively.");
            }

            return shouldKeepTileEntity(specialRegistered, vanillaRegistered);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static boolean shouldKeepTileEntity(boolean specialRegistered, @Nullable Boolean vanillaRegistered)
    {
        return specialRegistered || vanillaRegistered == null || vanillaRegistered;
    }

    private static boolean hasResolvedEntities(NBTTagCompound root)
    {
        if (!root.hasKey("missingEntities", Constants.NBT.TAG_LIST))
            return false;

        NBTTagList list = root.getTagList("missingEntities", Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++)
        {
            String id = list.getStringTagAt(i);
            NBTTagCompound stub = new NBTTagCompound();
            stub.setString("id", id);
            normalizeLegacyEntityId(stub);
            if (isKnownEntity(stub.getString("id")))
                return true;
        }

        return false;
    }

    private static boolean hasResolvedLootTables(NBTTagCompound root)
    {
        if (!root.hasKey("missingLootTables", Constants.NBT.TAG_LIST))
            return false;

        NBTTagList list = root.getTagList("missingLootTables", Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++)
        {
            String key = list.getStringTagAt(i);
            if (WeightedItemCollectionRegistry.INSTANCE.has(key))
                return true;
        }

        return false;
    }

    public static class SanitizationResult
    {
        final NBTTagCompound worldData;
        final Set<String> missingBlocks = new HashSet<>();
        final Set<String> missingTileEntities = new HashSet<>();
        final Set<String> missingEntities = new HashSet<>();
        final Set<String> missingLootTables = new HashSet<>();

        SanitizationResult(NBTTagCompound worldData)
        {
            this.worldData = worldData;
        }

        public NBTTagCompound getWorldData()
        {
            return worldData;
        }

        public boolean hasMissingEntries()
        {
            return !(missingBlocks.isEmpty() && missingTileEntities.isEmpty() && missingEntities.isEmpty() && missingLootTables.isEmpty());
        }

        void recordMissingBlock(@Nullable String id)
        {
            if (id != null && !id.isEmpty())
                missingBlocks.add(id);
        }

        void recordMissingTileEntity(@Nullable String id)
        {
            if (id != null && !id.isEmpty())
                missingTileEntities.add(id);
        }

        void recordMissingEntity(@Nullable String id)
        {
            if (id != null && !id.isEmpty())
                missingEntities.add(id);
        }

        void recordMissingLootTable(@Nullable String key)
        {
            if (key != null && !key.isEmpty())
                missingLootTables.add(key);
        }

        public Set<String> getMissingBlocks()
        {
            return Collections.unmodifiableSet(missingBlocks);
        }

        public Set<String> getMissingTileEntities()
        {
            return Collections.unmodifiableSet(missingTileEntities);
        }

        public Set<String> getMissingEntities()
        {
            return Collections.unmodifiableSet(missingEntities);
        }

        public Set<String> getMissingLootTables()
        {
            return Collections.unmodifiableSet(missingLootTables);
        }
    }
}
