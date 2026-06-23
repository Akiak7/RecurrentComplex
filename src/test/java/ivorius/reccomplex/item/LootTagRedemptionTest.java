package ivorius.reccomplex.item;

import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;

public class LootTagRedemptionTest
{
    private final Item rewardItem = new Item();
    private final Item otherItem = new Item();
    private final Item tokenItem = new Item();

    @BeforeClass
    public static void bootstrap()
    {
        if (!Bootstrap.isRegistered())
            Bootstrap.register();
    }

    @Test
    public void fullInventoryRejectsAndKeepsToken()
    {
        ItemStackHandler target = new ItemStackHandler(1);
        target.setStackInSlot(0, new ItemStack(otherItem, 64));

        ItemStack token = new ItemStack(tokenItem);
        boolean redeemed = LootTagRedemption.redeemIntoInventory(null, target,
                fixed(new ItemStack(rewardItem)), token, new Random(1), true);

        Assert.assertFalse(redeemed);
        Assert.assertEquals(otherItem, target.getStackInSlot(0).getItem());
        Assert.assertEquals(64, target.getStackInSlot(0).getCount());
        Assert.assertEquals(1, token.getCount());
    }

    @Test
    public void failedPartialInsertRollsInventoryBack()
    {
        ItemStackHandler target = new ItemStackHandler(2);
        target.setStackInSlot(1, new ItemStack(otherItem, 64));

        ItemStack token = new ItemStack(tokenItem);
        boolean redeemed = LootTagRedemption.redeemIntoInventory(null, target,
                fixed(new ItemStack(rewardItem), new ItemStack(otherItem)), token, new Random(1), true);

        Assert.assertFalse(redeemed);
        Assert.assertTrue(target.getStackInSlot(0).isEmpty());
        Assert.assertEquals(otherItem, target.getStackInSlot(1).getItem());
        Assert.assertEquals(64, target.getStackInSlot(1).getCount());
        Assert.assertEquals(1, token.getCount());
    }

    @Test
    public void partialInventoryAcceptsWhenEverythingFits()
    {
        ItemStackHandler target = new ItemStackHandler(2);
        target.setStackInSlot(0, new ItemStack(rewardItem, 63));

        ItemStack token = new ItemStack(tokenItem);
        boolean redeemed = LootTagRedemption.redeemIntoInventory(null, target,
                fixed(new ItemStack(rewardItem), new ItemStack(otherItem)), token, new Random(1), true);

        Assert.assertTrue(redeemed);
        Assert.assertEquals(rewardItem, target.getStackInSlot(0).getItem());
        Assert.assertEquals(64, target.getStackInSlot(0).getCount());
        Assert.assertEquals(otherItem, target.getStackInSlot(1).getItem());
        Assert.assertEquals(0, token.getCount());
    }

    @Test
    public void survivalRedemptionConsumesOneToken()
    {
        ItemStackHandler target = new ItemStackHandler(1);
        ItemStack token = new ItemStack(tokenItem, 3);

        boolean redeemed = LootTagRedemption.redeemIntoInventory(null, target,
                fixed(new ItemStack(rewardItem)), token, new Random(1), true);

        Assert.assertTrue(redeemed);
        Assert.assertEquals(rewardItem, target.getStackInSlot(0).getItem());
        Assert.assertEquals(2, token.getCount());
    }

    @Test
    public void adminRedemptionDoesNotConsumeToken()
    {
        ItemStackHandler target = new ItemStackHandler(1);
        ItemStack token = new ItemStack(tokenItem, 3);

        boolean redeemed = LootTagRedemption.redeemIntoInventory(null, target,
                fixed(new ItemStack(rewardItem)), token, new Random(1), false);

        Assert.assertTrue(redeemed);
        Assert.assertEquals(rewardItem, target.getStackInSlot(0).getItem());
        Assert.assertEquals(3, token.getCount());
    }

    @Test
    public void emptyValidRollConsumesTokenWithoutMutatingInventory()
    {
        ItemStackHandler target = new ItemStackHandler(1);
        target.setStackInSlot(0, new ItemStack(otherItem, 64));
        ItemStack token = new ItemStack(tokenItem);

        boolean redeemed = LootTagRedemption.redeemIntoInventory(null, target,
                fixed(), token, new Random(1), true);

        Assert.assertTrue(redeemed);
        Assert.assertEquals(otherItem, target.getStackInSlot(0).getItem());
        Assert.assertEquals(64, target.getStackInSlot(0).getCount());
        Assert.assertEquals(0, token.getCount());
    }

    private static GeneratingItem fixed(ItemStack... stacks)
    {
        return new GeneratingItem()
        {
            @Override
            public void generateInInventory(WorldServer server, IItemHandlerModifiable inventory, Random random, ItemStack stack, int fromSlot)
            {
                for (int i = 0; i < stacks.length && i < inventory.getSlots(); i++)
                    inventory.setStackInSlot(i, stacks[i].copy());
            }
        };
    }
}
