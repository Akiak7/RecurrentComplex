package ivorius.reccomplex.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import ivorius.reccomplex.item.ItemEventHandler;
import ivorius.reccomplex.item.ItemSyncable;
import net.minecraft.init.Bootstrap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class InventoryItemHandlerTest
{
    @BeforeClass
    public static void bootstrap()
    {
        if (!Bootstrap.isRegistered())
            Bootstrap.register();
    }

    @Test
    public void unrelatedItemsAreIgnored()
    {
        ItemStack stack = new ItemStack(new Item());

        new PacketItemEventHandler().affectItem(null, stack, new PacketItemEvent(0, Unpooled.buffer(), "ignored"));

        PacketSyncItem sync = new PacketSyncItem();
        sync.data = new NBTTagCompound();
        new PacketSyncItemHandler().affectItem(null, stack, sync);
    }

    @Test
    public void eventItemsReceiveEvents()
    {
        EventItem item = new EventItem();
        ItemStack stack = new ItemStack(item);
        ByteBuf payload = Unpooled.buffer();

        new PacketItemEventHandler().affectItem(null, stack, new PacketItemEvent(3, payload, "test"));

        Assert.assertTrue(item.called);
        Assert.assertEquals("test", item.context);
        Assert.assertSame(payload, item.payload);
        Assert.assertEquals(3, item.slot);
    }

    @Test
    public void syncableItemsReceiveNbt()
    {
        SyncItem item = new SyncItem();
        ItemStack stack = new ItemStack(item);
        PacketSyncItem message = new PacketSyncItem();
        message.data = new NBTTagCompound();
        message.data.setString("value", "synced");

        new PacketSyncItemHandler().affectItem(null, stack, message);

        Assert.assertEquals("synced", item.value);
    }

    private static class EventItem extends Item implements ItemEventHandler
    {
        boolean called;
        String context;
        ByteBuf payload;
        int slot;

        @Override
        public void onClientEvent(String context, ByteBuf payload, EntityPlayer sender, ItemStack stack, int itemSlot)
        {
            called = true;
            this.context = context;
            this.payload = payload;
            slot = itemSlot;
        }
    }

    private static class SyncItem extends Item implements ItemSyncable
    {
        String value;

        @Override
        public void writeSyncedNBT(NBTTagCompound compound, ItemStack stack)
        {
        }

        @Override
        public void readSyncedNBT(NBTTagCompound compound, ItemStack stack)
        {
            value = compound.getString("value");
        }
    }
}
