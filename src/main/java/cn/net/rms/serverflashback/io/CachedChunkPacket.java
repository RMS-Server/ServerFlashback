package cn.net.rms.serverflashback.io;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class CachedChunkPacket {
    private final int x;
    private final int z;
    private final byte[] bigHash;
    private final int hashCode;
    public int index;

    public CachedChunkPacket(ClientboundLevelChunkWithLightPacket packet, int index) {
        this.x = packet.getX();
        this.z = packet.getZ();
        this.bigHash = computePacketBigHash(packet);
        this.hashCode = Arrays.hashCode(this.bigHash);
        this.index = index;
    }

    private static byte[] computePacketBigHash(ClientboundLevelChunkWithLightPacket packet) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e2) {
                throw new RuntimeException(e2);
            }
        }

        digest.update(intToByteArray(packet.getX()));
        digest.update(intToByteArray(packet.getZ()));

//#if MC >= 11800
        FriendlyByteBuf readBuf = packet.getChunkData().getReadBuffer();
        byte[] chunkBytes = new byte[readBuf.readableBytes()];
        readBuf.getBytes(readBuf.readerIndex(), chunkBytes);
        digest.update(chunkBytes);

        FriendlyByteBuf frenBuffer = new FriendlyByteBuf(Unpooled.buffer());
        packet.getLightData().write(frenBuffer);
        byte[] lightBytes = new byte[frenBuffer.writerIndex()];
        frenBuffer.getBytes(0, lightBytes);
        digest.update(lightBytes);
        frenBuffer.release();

        for (var beInfo : packet.getChunkData().blockEntitiesData) {
            digest.update(intToByteArray(beInfo.packedXZ));
            digest.update(intToByteArray(beInfo.y));
            if (beInfo.tag != null) {
                FriendlyByteBuf tagBuf = new FriendlyByteBuf(Unpooled.buffer());
                tagBuf.writeNbt(beInfo.tag);
                byte[] tagBytes = new byte[tagBuf.writerIndex()];
                tagBuf.getBytes(0, tagBytes);
                digest.update(tagBytes);
                tagBuf.release();
            }
        }
//#else
//$$         FriendlyByteBuf frenBuffer = new FriendlyByteBuf(Unpooled.buffer());
//$$         packet.write(frenBuffer);
//$$         byte[] packetBytes = new byte[frenBuffer.writerIndex()];
//$$         frenBuffer.getBytes(0, packetBytes);
//$$         digest.update(packetBytes);
//$$         frenBuffer.release();
//#endif

        return digest.digest();
    }

    private static byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }

    @Override
    public int hashCode() {
        return this.hashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CachedChunkPacket that)) return false;
        if (this.hashCode() != that.hashCode()) return false;
        if (this.x != that.x || this.z != that.z) return false;
        return Arrays.compare(this.bigHash, that.bigHash) == 0;
    }
}
