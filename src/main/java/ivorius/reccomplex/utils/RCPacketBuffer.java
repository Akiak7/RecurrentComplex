/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://ivorius.net
 */

package ivorius.reccomplex.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Created by lukas on 30.01.17.
 */
public class RCPacketBuffer extends PacketBuffer
{
    // From SPacketCustomPayload, matching OperationRegistry's send-size guard.
    public static final int MAX_LARGE_STRING_BYTES = 1048576 * 4 / 5;

    public RCPacketBuffer(ByteBuf wrapped)
    {
        super(wrapped);
    }

    public void writeLargeString(String string)
    {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LARGE_STRING_BYTES)
            throw new EncoderException(String.format("String is too large (%d bytes, max %d)", bytes.length, MAX_LARGE_STRING_BYTES));

        writeInt(bytes.length);
        writeBytes(bytes);
    }

    public String readLargeString()
    {
        int length = readInt();
        if (length < 0 || length > MAX_LARGE_STRING_BYTES)
            throw new DecoderException(String.format("String length is invalid (%d bytes, max %d)", length, MAX_LARGE_STRING_BYTES));
        if (length > readableBytes())
            throw new DecoderException(String.format("String length exceeds readable bytes (%d > %d)", length, readableBytes()));

        String string = toString(readerIndex(), length, StandardCharsets.UTF_8);
        readerIndex(readerIndex() + length);
        return string;
    }

    @Nullable
    public NBTTagCompound readBigTag() throws IOException
    {
        int i = this.readerIndex();
        byte b0 = this.readByte();

        if (b0 == 0)
        {
            return null;
        }
        else
        {
            this.readerIndex(i);

            try
            {
                return CompressedStreamTools.read(new ByteBufInputStream(this), new NBTSizeTracker(2097152L * 4));
            }
            catch (IOException ioexception)
            {
                throw new EncoderException(ioexception);
            }
        }
    }
}
