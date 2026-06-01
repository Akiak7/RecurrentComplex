package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import ivorius.ivtoolkit.tools.MCRegistryDefault;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class WeightedBlockStateTest
{
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(WeightedBlockState.class, new WeightedBlockState.Serializer(MCRegistryDefault.INSTANCE))
            .create();

    @BeforeClass
    public static void bootstrapMinecraft()
    {
        Bootstrap.register();
    }

    @Test
    public void serializesTileEntityAsJsonObject()
    {
        WeightedBlockState state = new WeightedBlockState(null, Blocks.CHEST.getDefaultState(), chestTileEntity("RoundTrip", 42));

        JsonObject json = GSON.toJsonTree(state, WeightedBlockState.class).getAsJsonObject();
        Assert.assertTrue(json.has("tileEntity"));
        Assert.assertTrue(json.get("tileEntity").isJsonObject());

        assertTileEntity(GSON.fromJson(json, WeightedBlockState.class), "RoundTrip", 42);
    }

    @Test
    public void omitsNullTileEntity()
    {
        WeightedBlockState state = new WeightedBlockState(null, Blocks.CHEST.getDefaultState(), null);

        JsonObject json = GSON.toJsonTree(state, WeightedBlockState.class).getAsJsonObject();
        Assert.assertFalse(json.has("tileEntity"));
    }

    @Test
    public void readsLegacyTileEntityInfoString()
    {
        JsonObject json = chestReplacementJson();
        json.addProperty("tileEntityInfo", "{id:\"minecraft:chest\",CustomName:\"Legacy\",LootSeed:12}");

        assertTileEntity(GSON.fromJson(json, WeightedBlockState.class), "Legacy", 12);
    }

    @Test
    public void readsStringifiedTileEntityJson()
    {
        JsonObject json = chestReplacementJson();
        json.addProperty("tileEntity", WeightedBlockState.getGson().toJson(chestTileEntity("Stringified", 24)));

        assertTileEntity(GSON.fromJson(json, WeightedBlockState.class), "Stringified", 24);
    }

    @Test
    public void readsStringifiedNullTileEntityAsMissing()
    {
        JsonObject json = chestReplacementJson();
        json.addProperty("tileEntity", "null");

        WeightedBlockState state = GSON.fromJson(json, WeightedBlockState.class);
        Assert.assertNull(state.tileEntityInfo);
    }

    private static JsonObject chestReplacementJson()
    {
        JsonObject json = new JsonObject();
        json.addProperty("block", "minecraft:chest");
        json.addProperty("metadata", 0);
        return json;
    }

    private static NBTTagCompound chestTileEntity(String customName, int lootSeed)
    {
        NBTTagCompound compound = new NBTTagCompound();
        compound.setString("id", "minecraft:chest");
        compound.setString("CustomName", customName);
        compound.setInteger("LootSeed", lootSeed);
        return compound;
    }

    private static void assertTileEntity(WeightedBlockState state, String customName, int lootSeed)
    {
        Assert.assertNotNull(state.tileEntityInfo);
        Assert.assertEquals("minecraft:chest", state.tileEntityInfo.getString("id"));
        Assert.assertEquals(customName, state.tileEntityInfo.getString("CustomName"));
        Assert.assertEquals(lootSeed, state.tileEntityInfo.getInteger("LootSeed"));
    }
}
