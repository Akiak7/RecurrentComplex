package ivorius.reccomplex.network;

import ivorius.reccomplex.RecurrentComplex;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ServerPacketAuthorizationTest
{
    private EntityPlayerMP player;
    private MessageContext context;

    @Before
    public void setUp()
    {
        player = mock(EntityPlayerMP.class);
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
}
