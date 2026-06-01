package ivorius.reccomplex.network;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import ivorius.reccomplex.files.loading.ResourceDirectory;
import ivorius.reccomplex.utils.SaveDirectoryData;
import ivorius.reccomplex.world.gen.feature.structure.generic.GenericStructure;
import ivorius.reccomplex.world.storage.loot.GenericLootTable;
import ivorius.reccomplex.world.storage.loot.ItemCollectionSaveHandler;
import net.minecraft.init.Bootstrap;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

public class EditorPayloadPacketTest
{
    @BeforeClass
    public static void bootstrapMinecraft()
    {
        Bootstrap.register();
    }

    @Test
    public void editStructurePacketRoundTripsLargeStructureJson()
    {
        String largeComment = repeat("structure payload ", 1500);
        GenericStructure structure = structureWithComment(largeComment);
        SaveDirectoryData saveDirectoryData = new SaveDirectoryData(ResourceDirectory.ACTIVE, true, Collections.emptySet(), Collections.emptySet());

        PacketEditStructure packet = new PacketEditStructure(structure, "large_structure", new net.minecraft.util.math.BlockPos(1, 2, 3), saveDirectoryData);
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);

        PacketEditStructure decoded = new PacketEditStructure();
        decoded.fromBytes(buffer);

        Assert.assertEquals("large_structure", decoded.getStructureID());
        Assert.assertEquals(largeComment, decoded.getStructureInfo().metadata.comment);
        Assert.assertEquals(new net.minecraft.util.math.BlockPos(1, 2, 3), decoded.getLowerCoord());
        Assert.assertEquals(ResourceDirectory.ACTIVE, decoded.getSaveDirectoryData().getDirectory());
    }

    @Test
    public void saveStructurePacketRoundTripsLargeStructureJson()
    {
        String largeComment = repeat("saved structure payload ", 1200);
        GenericStructure structure = structureWithComment(largeComment);

        PacketSaveStructure packet = new PacketSaveStructure(structure, "large_structure", new SaveDirectoryData.Result(ResourceDirectory.INACTIVE, true));
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);

        PacketSaveStructure decoded = new PacketSaveStructure();
        decoded.fromBytes(buffer);

        Assert.assertEquals("large_structure", decoded.getStructureID());
        Assert.assertEquals(largeComment, decoded.getStructureInfo().metadata.comment);
        Assert.assertEquals(ResourceDirectory.INACTIVE, decoded.getSaveDirectoryDataResult().directory);
        Assert.assertTrue(decoded.getSaveDirectoryDataResult().deleteOther);
    }

    @Test
    public void lootComponentHandlerRoundTripsLargeJson()
    {
        String largeTableID = repeat("loot payload ", 1500);
        GenericLootTable.Component component = new GenericLootTable.Component();
        component.tableID = largeTableID;

        ByteBuf buffer = Unpooled.buffer();
        ItemCollectionSaveHandler.INSTANCE.write(buffer, component);

        GenericLootTable.Component decoded = ItemCollectionSaveHandler.INSTANCE.read(buffer);

        Assert.assertNotNull(decoded);
        Assert.assertEquals(largeTableID, decoded.tableID);
    }

    @Test
    public void editLootTablePacketRoundTripsLargeLootJson()
    {
        String largeTableID = repeat("edit loot payload ", 1200);
        GenericLootTable.Component component = new GenericLootTable.Component();
        component.tableID = largeTableID;

        PacketEditLootTable packet = new PacketEditLootTable("large_loot", component,
                new SaveDirectoryData(ResourceDirectory.ACTIVE, false, Collections.emptySet(), Collections.emptySet()));
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);

        PacketEditLootTable decoded = new PacketEditLootTable();
        decoded.fromBytes(buffer);

        Assert.assertEquals("large_loot", decoded.getKey());
        Assert.assertEquals(largeTableID, decoded.getComponent().tableID);
        Assert.assertEquals(ResourceDirectory.ACTIVE, decoded.getSaveDirectoryData().getDirectory());
        Assert.assertFalse(decoded.getSaveDirectoryData().isDeleteOther());
    }

    @Test
    public void saveLootTablePacketRoundTripsLargeLootJson()
    {
        String largeTableID = repeat("save loot payload ", 1200);
        GenericLootTable.Component component = new GenericLootTable.Component();
        component.tableID = largeTableID;

        PacketSaveLootTable packet = new PacketSaveLootTable("large_loot", component,
                new SaveDirectoryData.Result(ResourceDirectory.INACTIVE, false));
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);

        PacketSaveLootTable decoded = new PacketSaveLootTable();
        decoded.fromBytes(buffer);

        Assert.assertEquals("large_loot", decoded.getKey());
        Assert.assertEquals(largeTableID, decoded.getComponent().tableID);
        Assert.assertEquals(ResourceDirectory.INACTIVE, decoded.getSaveDirectoryDataResult().directory);
        Assert.assertFalse(decoded.getSaveDirectoryDataResult().deleteOther);
    }

    private static GenericStructure structureWithComment(String comment)
    {
        GenericStructure structure = new GenericStructure();
        structure.customData = new JsonObject();
        structure.metadata.comment = comment;
        return structure;
    }

    private static String repeat(String string, int times)
    {
        StringBuilder builder = new StringBuilder(string.length() * times);
        for (int i = 0; i < times; i++)
            builder.append(string);
        return builder.toString();
    }
}
