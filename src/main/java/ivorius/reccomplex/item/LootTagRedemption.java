/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.item;

import ivorius.ivtoolkit.gui.IntegerRange;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.utils.ItemHandlers;
import ivorius.reccomplex.world.storage.loot.LootGenerationHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LootTagRedemption
{
    private static final int MAX_SCRATCH_SLOTS = 1024;

    public static boolean canEditLootTags(@Nullable EntityPlayer player)
    {
        return player != null && (player.capabilities.isCreativeMode || player.canUseCommand(2, "give"));
    }

    public static boolean canAttemptManualRedemption(@Nullable EntityPlayer player)
    {
        return canEditLootTags(player) || (player != null && player.isSneaking());
    }

    public static EnumActionResult redeemHeldOnInventory(EntityPlayer player, World world, BlockPos pos, EnumHand hand, GeneratingItem generatingItem)
    {
        if (!canAttemptManualRedemption(player))
            return EnumActionResult.PASS;

        TileEntity rightClicked = world.getTileEntity(pos);
        if (rightClicked == null || !ItemHandlers.hasModifiable(rightClicked))
            return EnumActionResult.PASS;

        if (world.isRemote)
            return EnumActionResult.SUCCESS;

        IItemHandlerModifiable itemHandler = ItemHandlers.getModifiable(rightClicked);
        if (itemHandler == null)
            return EnumActionResult.PASS;

        ItemStack heldStack = player.getHeldItem(hand);
        boolean consume = !canEditLootTags(player);

        return redeemIntoInventory((WorldServer) world, itemHandler, generatingItem, heldStack, world.rand, consume)
                ? EnumActionResult.SUCCESS
                : EnumActionResult.PASS;
    }

    public static boolean redeemIntoInventory(@Nullable WorldServer server, IItemHandlerModifiable inventory, GeneratingItem generatingItem, ItemStack source, Random random, boolean consumeSource)
    {
        if (source.isEmpty() || !hasValidSource(generatingItem, source))
            return false;

        int scratchSlots = scratchSlots(inventory, generatingItem, source);
        if (scratchSlots <= 0)
            return false;

        ItemStackHandler scratch = new ItemStackHandler(scratchSlots);
        generatingItem.generateInInventory(server, scratch, random, source.copy(), 0);

        if (server != null)
            LootGenerationHandler.generateAllTags(server, scratch, RecurrentComplex.specialRegistry.itemHidingMode(), random);

        List<ItemStack> generated = generatedStacks(scratch);
        if (!generated.isEmpty() && !insertAllTransactionally(inventory, generated))
            return false;

        if (consumeSource)
            source.shrink(1);

        return true;
    }

    private static boolean hasValidSource(GeneratingItem generatingItem, ItemStack source)
    {
        if (generatingItem instanceof ItemLootGenerationTag)
            return ItemLootGenerationTag.lootTable(source) != null;
        if (generatingItem instanceof ItemLootTableComponentTag)
            return ItemLootTableComponentTag.component(source) != null;

        return true;
    }

    private static int scratchSlots(IItemHandler target, GeneratingItem generatingItem, ItemStack source)
    {
        int slots = Math.max(1, target.getSlots());

        if (generatingItem instanceof ItemLootGenMultiTag)
        {
            IntegerRange range = ((ItemLootGenMultiTag) generatingItem).getGenerationCount(source);
            slots = Math.max(slots, Math.max(range.getMin(), range.getMax()));
        }

        return slots <= MAX_SCRATCH_SLOTS ? slots : -1;
    }

    @Nonnull
    private static List<ItemStack> generatedStacks(IItemHandler inventory)
    {
        List<ItemStack> generated = new ArrayList<>();

        for (int i = 0; i < inventory.getSlots(); i++)
        {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty())
                generated.add(stack.copy());
        }

        return generated;
    }

    private static boolean insertAllTransactionally(IItemHandlerModifiable inventory, List<ItemStack> stacks)
    {
        ItemStack[] snapshot = snapshot(inventory);

        try
        {
            if (insertAll(inventory, stacks))
                return true;
        }
        catch (RuntimeException ignored)
        {
            // Some modded inventories enforce rules during insertion; failed redemptions must stay non-destructive.
        }

        restore(inventory, snapshot);
        return false;
    }

    private static boolean insertAll(IItemHandler inventory, List<ItemStack> stacks)
    {
        for (ItemStack stack : stacks)
        {
            ItemStack remaining = insertStack(inventory, stack.copy());
            if (!remaining.isEmpty())
                return false;
        }

        return true;
    }

    private static ItemStack insertStack(IItemHandler inventory, ItemStack stack)
    {
        ItemStack remaining = stack;

        for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++)
            remaining = inventory.insertItem(slot, remaining, false);

        return remaining;
    }

    private static ItemStack[] snapshot(IItemHandler inventory)
    {
        ItemStack[] snapshot = new ItemStack[inventory.getSlots()];

        for (int i = 0; i < snapshot.length; i++)
            snapshot[i] = inventory.getStackInSlot(i).copy();

        return snapshot;
    }

    private static void restore(IItemHandlerModifiable inventory, ItemStack[] snapshot)
    {
        for (int i = 0; i < snapshot.length; i++)
            inventory.setStackInSlot(i, snapshot[i].copy());
    }
}
