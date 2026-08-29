/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.network;

import ivorius.reccomplex.item.ItemSyncable;
import ivorius.reccomplex.item.ItemLootGenerationTag;
import ivorius.reccomplex.item.LootTagRedemption;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * Created by lukas on 17.01.15.
 */
public class PacketSyncItemHandler extends PacketEditInventoryItemHandler<PacketSyncItem>
{
    @Override
    protected boolean hasEditPermission(EntityPlayerMP player, PacketSyncItem message)
    {
        if (super.hasEditPermission(player, message))
            return true;

        if (!LootTagRedemption.canEditLootTags(player))
            return false;

        return canSyncLootTagWithoutSavingPermission(player,
                player.inventory.getStackInSlot(message.getInventorySlot()));
    }

    static boolean canSyncLootTagWithoutSavingPermission(EntityPlayerMP player, ItemStack stack)
    {
        return !stack.isEmpty()
                && stack.getItem() instanceof ItemLootGenerationTag
                && LootTagRedemption.canEditLootTags(player);
    }

    @Override
    public void affectItem(EntityPlayerMP player, ItemStack stack, PacketSyncItem message)
    {
        if (stack.getItem() instanceof ItemSyncable)
        {
            if (stack.getItem() instanceof ItemLootGenerationTag && !LootTagRedemption.canEditLootTags(player))
                return;

            ItemSyncable itemSyncable = (ItemSyncable) stack.getItem();
            itemSyncable.readSyncedNBT(message.data, stack);
        }
    }
}
