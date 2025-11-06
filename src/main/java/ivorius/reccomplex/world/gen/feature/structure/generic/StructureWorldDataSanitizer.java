package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.common.hash.Hashing;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.world.storage.loot.WeightedItemCollectionRegistry;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
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
import java.util.Set;

import static net.minecraft.nbt.CompressedStreamTools.readCompressed;
import static net.minecraft.nbt.CompressedStreamTools.writeCompressed;

/**
 * Sanitizes structure world data so that missing content is removed before generation.
 */
public class StructureWorldDataSanitizer
{
    private static final String LOOT_TAG_ITEM = new ResourceLocation(RecurrentComplex.MOD_ID, "inventory_generation_tag").toString();

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
        String airName = Blocks.AIR.getRegistryName() != null ? Blocks.AIR.getRegistryName().toString() : "minecraft:air";

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
                    }
                }
            }
        }
    }

    private static boolean isKnownBlock(@Nullable String blockId)
    {
        if (blockId == null || blockId.isEmpty())
            return false;

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
            boolean keep = true;

            try
            {
                keep = TileEntity.create(null, tileEntity) != null;
            }
            catch (Exception e)
            {
                keep = false;
            }

            if (keep)
            {
                if (sanitizeLootItems(tileEntity, result))
                    changed = true;
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

    private static boolean sanitizeLootItems(NBTTagCompound tileEntity, SanitizationResult result)
    {
        if (!tileEntity.hasKey("Items", Constants.NBT.TAG_LIST))
            return false;

        NBTTagList items = tileEntity.getTagList("Items", Constants.NBT.TAG_COMPOUND);
        NBTTagList sanitized = new NBTTagList();
        boolean changed = false;

        for (int i = 0; i < items.tagCount(); i++)
        {
            NBTTagCompound item = items.getCompoundTagAt(i);
            if (isInvalidLootItem(item, result))
            {
                changed = true;
                continue;
            }

            sanitized.appendTag(item);
        }

        if (changed)
            tileEntity.setTag("Items", sanitized);

        return changed;
    }

    private static boolean isInvalidLootItem(NBTTagCompound item, SanitizationResult result)
    {
        if (!item.hasKey("id", Constants.NBT.TAG_STRING))
            return false;

        String itemId = item.getString("id");

        if (!isKnownItem(itemId))
        {
            result.recordMissingItem(itemId);
            return true;
        }

        if (!LOOT_TAG_ITEM.equals(itemId))
            return false;

        if (!item.hasKey("tag", Constants.NBT.TAG_COMPOUND))
            return true;

        NBTTagCompound tag = item.getCompoundTag("tag");
        if (!tag.hasKey("itemCollectionKey", Constants.NBT.TAG_STRING))
            return true;

        String key = tag.getString("itemCollectionKey");
        if (key.isEmpty())
            return true;

        boolean known = WeightedItemCollectionRegistry.INSTANCE.has(key);
        if (!known)
            result.recordMissingLootTable(key);
        return !known;
    }

    private static boolean isKnownItem(@Nullable String itemId)
    {
        if (itemId == null || itemId.isEmpty())
            return false;

        try
        {
            ResourceLocation location = new ResourceLocation(itemId);
            return ForgeRegistries.ITEMS.containsKey(location);
        }
        catch (Exception ignored)
        {
            return false;
        }
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
        root.setString("sourceHash", hash);
        root.setTag("worldData", result.worldData);
        writeStringSet(root, "missingBlocks", result.missingBlocks);
        writeStringSet(root, "missingTileEntities", result.missingTileEntities);
        writeStringSet(root, "missingEntities", result.missingEntities);
        writeStringSet(root, "missingLootTables", result.missingLootTables);
        writeStringSet(root, "missingItems", result.missingItems);

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
        if (hasResolvedLootTables(root))
            return true;
        return hasResolvedItems(root);
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

    private static boolean isKnownTileEntity(@Nullable String tileEntityId)
    {
        if (tileEntityId == null || tileEntityId.isEmpty())
            return false;

        try
        {
            NBTTagCompound stub = new NBTTagCompound();
            stub.setString("id", tileEntityId);
            return TileEntity.create(null, stub) != null;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static boolean hasResolvedEntities(NBTTagCompound root)
    {
        if (!root.hasKey("missingEntities", Constants.NBT.TAG_LIST))
            return false;

        NBTTagList list = root.getTagList("missingEntities", Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++)
        {
            String id = list.getStringTagAt(i);
            if (isKnownEntity(id))
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

    private static boolean hasResolvedItems(NBTTagCompound root)
    {
        if (!root.hasKey("missingItems", Constants.NBT.TAG_LIST))
            return false;

        NBTTagList list = root.getTagList("missingItems", Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.tagCount(); i++)
        {
            String id = list.getStringTagAt(i);
            if (isKnownItem(id))
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
        final Set<String> missingItems = new HashSet<>();

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
            return !(missingBlocks.isEmpty() && missingTileEntities.isEmpty() && missingEntities.isEmpty() && missingLootTables.isEmpty() && missingItems.isEmpty());
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

        void recordMissingItem(@Nullable String id)
        {
            if (id != null && !id.isEmpty())
                missingItems.add(id);
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

        public Set<String> getMissingItems()
        {
            return Collections.unmodifiableSet(missingItems);
        }
    }
}
