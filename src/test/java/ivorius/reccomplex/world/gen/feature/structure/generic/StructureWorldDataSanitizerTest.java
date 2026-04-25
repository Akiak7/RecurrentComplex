package ivorius.reccomplex.world.gen.feature.structure.generic;

import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class StructureWorldDataSanitizerTest
{
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
}
