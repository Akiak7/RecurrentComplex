/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.network;

import ivorius.ivtoolkit.network.SchedulingMessageHandler;
import ivorius.reccomplex.RecurrentComplex;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Created by lukas on 17.01.15.
 */
public abstract class PacketEditInventoryItemHandler<P extends PacketEditInventoryItem> extends SchedulingMessageHandler<P, IMessage>
{
    @Override
    public void processServer(P message, MessageContext ctx, WorldServer server)
    {
        NetHandlerPlayServer playServer = ctx.getServerHandler();
        EntityPlayerMP player = playServer.player;

        if (!hasEditPermission(player, message))
        {
            RecurrentComplex.checkPerms(player);
            return;
        }

        int inventorySlot = message.getInventorySlot();
        if (!isInventorySlotValid(player, inventorySlot))
            return;

        affectItem(player, player.inventory.getStackInSlot(inventorySlot), message);
        player.openContainer.detectAndSendChanges();
    }

    static boolean isInventorySlotValid(EntityPlayerMP player, int inventorySlot)
    {
        return isInventorySlotValid(inventorySlot, player.inventory.getSizeInventory());
    }

    static boolean isInventorySlotValid(int inventorySlot, int inventorySize)
    {
        return inventorySlot >= 0 && inventorySlot < inventorySize;
    }

    protected boolean hasEditPermission(EntityPlayerMP player, P message)
    {
        return RecurrentComplex.canHandleSaving(player);
    }

    public abstract void affectItem(EntityPlayerMP player, ItemStack stack, P message);
}
