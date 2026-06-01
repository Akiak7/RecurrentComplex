package ivorius.reccomplex.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import org.junit.Assert;
import org.junit.Test;

public class RCPacketBufferTest
{
    @Test
    public void largeStringRoundTripsAboveForgeUtfLimit()
    {
        String string = repeat("a", 20000);
        ByteBuf buffer = Unpooled.buffer();

        new RCPacketBuffer(buffer).writeLargeString(string);

        Assert.assertEquals(string, new RCPacketBuffer(buffer).readLargeString());
        Assert.assertEquals(0, buffer.readableBytes());
    }

    @Test
    public void forgeUtfHelperStillRejectsSameLargeString()
    {
        String string = repeat("a", 20000);

        Assert.assertThrows(IllegalArgumentException.class, () ->
                ByteBufUtils.writeUTF8String(Unpooled.buffer(), string));
    }

    @Test
    public void oversizedLargeStringIsRejectedOnWrite()
    {
        String string = repeat("a", RCPacketBuffer.MAX_LARGE_STRING_BYTES + 1);

        Assert.assertThrows(EncoderException.class, () ->
                new RCPacketBuffer(Unpooled.buffer()).writeLargeString(string));
    }

    @Test
    public void oversizedLargeStringIsRejectedOnRead()
    {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeInt(RCPacketBuffer.MAX_LARGE_STRING_BYTES + 1);

        Assert.assertThrows(DecoderException.class, () ->
                new RCPacketBuffer(buffer).readLargeString());
    }

    @Test
    public void negativeLargeStringLengthIsRejectedOnRead()
    {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeInt(-1);

        Assert.assertThrows(DecoderException.class, () ->
                new RCPacketBuffer(buffer).readLargeString());
    }

    private static String repeat(String string, int times)
    {
        StringBuilder builder = new StringBuilder(string.length() * times);
        for (int i = 0; i < times; i++)
            builder.append(string);
        return builder.toString();
    }
}
