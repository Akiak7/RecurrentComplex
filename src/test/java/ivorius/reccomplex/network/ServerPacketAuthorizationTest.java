package ivorius.reccomplex.network;

import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.item.ItemBlockSelectorFloating;
import ivorius.reccomplex.item.ItemLootGenSingleTag;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ServerPacketAuthorizationTest
{
    private EntityPlayerMP player;
    private MessageContext context;

    @BeforeClass
    public static void bootstrap()
    {
        if (!Bootstrap.isRegistered())
            Bootstrap.register();
    }

    @Before
    public void setUp()
    {
        player = mock(EntityPlayerMP.class);
        player.capabilities = new PlayerCapabilities();
        when(player.canUseCommand(2, "setblock")).thenReturn(false);

        NetHandlerPlayServer serverHandler = mock(NetHandlerPlayServer.class);
        serverHandler.player = player;

        context = mock(MessageContext.class);
        when(context.getServerHandler()).thenReturn(serverHandler);
    }

    @Test
    public void permissionHelperRecognizesOperators()
    {
        EntityPlayerMP operator = mock(EntityPlayerMP.class);
        when(operator.canUseCommand(2, "setblock")).thenReturn(true);

        Assert.assertTrue(RecurrentComplex.canHandleSaving(operator));
        Assert.assertFalse(RecurrentComplex.canHandleSaving(player));
    }

    @Test
    public void unauthorizedInventoryEditReturnsBeforePayloadAccess()
    {
        AtomicBoolean affected = new AtomicBoolean();
        PacketEditInventoryItemHandler<PacketSyncItem> handler = new PacketEditInventoryItemHandler<PacketSyncItem>()
        {
            @Override
            public void affectItem(EntityPlayerMP player, ItemStack stack, PacketSyncItem message)
            {
                affected.set(true);
            }
        };

        handler.processServer(null, context, null);

        Assert.assertFalse(affected.get());
    }

    @Test
    public void unauthorizedLootTagSyncReturnsBeforePayloadAccess()
    {
        new PacketSyncItemHandler().processServer(null, context, null);
    }

    @Test
    public void creativePlayerCanSyncLootTagsButNotOtherSyncableItems()
    {
        EntityPlayerMP creativePlayer = playerWithLootTagPermission(true, false);

        Assert.assertTrue(PacketSyncItemHandler.canSyncLootTagWithoutSavingPermission(creativePlayer,
                new ItemStack(new ItemLootGenSingleTag())));
        Assert.assertFalse(PacketSyncItemHandler.canSyncLootTagWithoutSavingPermission(creativePlayer,
                new ItemStack(new ItemBlockSelectorFloating())));
    }

    @Test
    public void givePermissionCanSyncLootTagsWithoutCreativeMode()
    {
        EntityPlayerMP permittedPlayer = playerWithLootTagPermission(false, true);

        Assert.assertTrue(PacketSyncItemHandler.canSyncLootTagWithoutSavingPermission(permittedPlayer,
                new ItemStack(new ItemLootGenSingleTag())));
    }

    @Test
    public void survivalPlayerCannotSyncLootTags()
    {
        EntityPlayerMP survivalPlayer = playerWithLootTagPermission(false, false);

        Assert.assertFalse(PacketSyncItemHandler.canSyncLootTagWithoutSavingPermission(survivalPlayer,
                new ItemStack(new ItemLootGenSingleTag())));
    }

    @Test
    public void inventorySlotValidationRejectsNegativeAndPastEndSlots()
    {
        Assert.assertFalse(PacketEditInventoryItemHandler.isInventorySlotValid(-1, 41));
        Assert.assertTrue(PacketEditInventoryItemHandler.isInventorySlotValid(0, 41));
        Assert.assertTrue(PacketEditInventoryItemHandler.isInventorySlotValid(40, 41));
        Assert.assertFalse(PacketEditInventoryItemHandler.isInventorySlotValid(41, 41));
    }

    @Test
    public void unauthorizedTileEntityEditReturnsBeforePayloadAccess()
    {
        new PacketEditTileEntityHandler().processServer(null, context, null);
    }

    @Test
    public void unauthorizedSpawnTweaksEditReturnsBeforePayloadAccess()
    {
        new PacketSpawnTweaksHandler().processServer(null, context, null);
    }

    @Test
    public void unauthorizedWorldDataEditReturnsBeforePayloadAccess()
    {
        new PacketWorldDataHandler().processServer(null, context, null);
    }

    private static EntityPlayerMP playerWithLootTagPermission(boolean creative, boolean canGive)
    {
        EntityPlayerMP player = mock(EntityPlayerMP.class);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = creative;
        when(player.canUseCommand(2, "give")).thenReturn(canGive);
        return player;
    }
}
