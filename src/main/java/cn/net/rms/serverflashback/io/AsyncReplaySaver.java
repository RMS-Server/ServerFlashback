package cn.net.rms.serverflashback.io;

import cn.net.rms.serverflashback.action.ActionConfigurationPacket;
import cn.net.rms.serverflashback.action.ActionGamePacket;
import cn.net.rms.serverflashback.action.ActionLevelChunkCached;
import cn.net.rms.serverflashback.util.SneakyThrow;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
//#if MC >= 12005
import net.minecraft.core.RegistryAccess;
//#endif
import net.minecraft.network.FriendlyByteBuf;
//#if MC >= 12005
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
//#else
//$$ import net.minecraft.network.ConnectionProtocol;
//$$ import net.minecraft.network.protocol.PacketFlow;
//#endif
import net.minecraft.network.protocol.Packet;
//#if MC >= 12002
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
//#else
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//#endif
//#if MC >= 12002
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
//#endif
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public class AsyncReplaySaver {

    private static final int CHUNK_CACHE_SIZE = 10000;

    private final ArrayBlockingQueue<Consumer<ReplayWriter>> tasks = new ArrayBlockingQueue<>(1024);
    private final AtomicReference<Throwable> error = new AtomicReference<>(null);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private final AtomicBoolean hasStopped = new AtomicBoolean(false);

    private final Path recordFolder;

//#if MC >= 12005
    public AsyncReplaySaver(RegistryAccess registryAccess, Path baseDir) {
//#else
//$$     public AsyncReplaySaver(Path baseDir) {
//#endif
        this.recordFolder = baseDir.resolve("serverflashback").resolve("temp").resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(this.recordFolder);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create recording directory", e);
        }

//#if MC >= 12005
        ReplayWriter replayWriter = new ReplayWriter(registryAccess);
//#else
//$$         ReplayWriter replayWriter = new ReplayWriter();
//#endif
        Thread writerThread = new Thread(() -> {
            while (true) {
                try {
                    Consumer<ReplayWriter> task = this.tasks.poll(10, TimeUnit.MILLISECONDS);
                    if (task == null) {
                        if (this.shouldStop.get()) {
                            this.hasStopped.set(true);
                            return;
                        }
                        continue;
                    }
                    task.accept(replayWriter);
                } catch (Throwable t) {
                    this.error.set(t);
                    this.hasStopped.set(true);
                    return;
                }
            }
        }, "ServerFlashback-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public void submit(Consumer<ReplayWriter> consumer) {
        this.checkForError();
        if (this.hasStopped.get()) {
            throw new IllegalStateException("Cannot submit task to AsyncReplaySaver that has already stopped");
        }
        while (true) {
            try {
                this.tasks.put(consumer);
                break;
            } catch (InterruptedException ignored) {}
        }
    }

    private final Int2ObjectMap<List<CachedChunkPacket>> cachedChunkPackets = new Int2ObjectOpenHashMap<>();
    private int totalWrittenChunkPackets = 0;

//#if MC >= 12005
    public void writeGamePackets(StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec,
                                 List<Packet<? super ClientGamePacketListener>> packets) {
//#else
//$$     public void writeGamePackets(List<Packet<? super ClientGamePacketListener>> packets) {
//#endif
        List<Packet<? super ClientGamePacketListener>> packetCopy = new ArrayList<>(packets);
        this.submit(writer -> {
//#if MC >= 12005
            RegistryFriendlyByteBuf chunkCacheOutput = null;
//#else
//$$             FriendlyByteBuf chunkCacheOutput = null;
//#endif
            int lastChunkCacheIndex = -1;
            FriendlyByteBuf customPayloadTempBuffer = null;

            for (Packet<? super ClientGamePacketListener> packet : packetCopy) {
                if (packet instanceof ClientboundLevelChunkWithLightPacket levelChunkPacket) {
                    int index = -1;
                    CachedChunkPacket cachedChunkPacket = new CachedChunkPacket(levelChunkPacket, -1);
                    int hashCode = cachedChunkPacket.hashCode();
                    boolean add = true;

                    List<CachedChunkPacket> cached = this.cachedChunkPackets.get(hashCode);
                    if (cached == null) {
                        cached = new ArrayList<>();
                        this.cachedChunkPackets.put(hashCode, cached);
                    } else {
                        for (CachedChunkPacket existing : cached) {
                            if (existing.equals(cachedChunkPacket)) {
                                add = false;
                                index = existing.index;
                                break;
                            }
                        }
                    }

                    if (add) {
                        index = this.totalWrittenChunkPackets;
                        this.totalWrittenChunkPackets += 1;

                        int cacheIndex = index / CHUNK_CACHE_SIZE;
                        if (lastChunkCacheIndex >= 0 && cacheIndex != lastChunkCacheIndex) {
                            this.writeChunkCacheFile(chunkCacheOutput, lastChunkCacheIndex);
                            chunkCacheOutput = null;
                        }
                        lastChunkCacheIndex = cacheIndex;

                        if (chunkCacheOutput == null) {
//#if MC >= 12005
                            chunkCacheOutput = new RegistryFriendlyByteBuf(Unpooled.buffer(), writer.registryAccess());
//#else
//$$                             chunkCacheOutput = new FriendlyByteBuf(Unpooled.buffer());
//#endif
                        }

                        int startWriterIndex = chunkCacheOutput.writerIndex();
                        chunkCacheOutput.writeInt(-1);
//#if MC >= 12005
                        gamePacketCodec.encode(chunkCacheOutput, packet);
//#else
//$$                         encodeGamePacket(chunkCacheOutput, packet);
//#endif
                        int endWriterIndex = chunkCacheOutput.writerIndex();
                        int size = endWriterIndex - startWriterIndex - 4;
                        chunkCacheOutput.writerIndex(startWriterIndex);
                        chunkCacheOutput.writeInt(size);
                        chunkCacheOutput.writerIndex(endWriterIndex);

                        cachedChunkPacket.index = index;
                        cached.add(cachedChunkPacket);
                    }

                    writer.startAction(ActionLevelChunkCached.INSTANCE);
                    writer.friendlyByteBuf().writeVarInt(index);
                    writer.finishAction(ActionLevelChunkCached.INSTANCE);
                    continue;
                }

                if (packet instanceof ClientboundCustomPayloadPacket) {
                    try {
                        if (customPayloadTempBuffer == null) {
//#if MC >= 12005
                            customPayloadTempBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), writer.registryAccess());
//#else
//$$                             customPayloadTempBuffer = new FriendlyByteBuf(Unpooled.buffer());
//#endif
                        }
                        customPayloadTempBuffer.clear();
//#if MC >= 12005
                        gamePacketCodec.encode(customPayloadTempBuffer, packet);
//#else
//$$                         encodeGamePacket(customPayloadTempBuffer, packet);
//#endif
                        writer.startAction(ActionGamePacket.INSTANCE);
                        writer.friendlyByteBuf().writeBytes(customPayloadTempBuffer);
                        writer.finishAction(ActionGamePacket.INSTANCE);
                    } catch (Exception ignored) {}
                } else {
                    writer.startAction(ActionGamePacket.INSTANCE);
//#if MC >= 12005
                    gamePacketCodec.encode(writer.friendlyByteBuf(), packet);
//#else
//$$                     encodeGamePacket(writer.friendlyByteBuf(), packet);
//#endif
                    writer.finishAction(ActionGamePacket.INSTANCE);
                }
            }

            if (lastChunkCacheIndex >= 0) {
                writeChunkCacheFile(chunkCacheOutput, lastChunkCacheIndex);
            }
        });
    }

//#if MC >= 12005
    private void writeChunkCacheFile(RegistryFriendlyByteBuf chunkCacheOutput, int index) {
//#else
//$$     private void writeChunkCacheFile(FriendlyByteBuf chunkCacheOutput, int index) {
//#endif
        if (chunkCacheOutput == null || chunkCacheOutput.writerIndex() == 0) return;
        try {
            byte[] bytes = new byte[chunkCacheOutput.writerIndex()];
            chunkCacheOutput.getBytes(0, bytes);
            Path path = this.recordFolder.resolve("level_chunk_caches").resolve("" + index);
            Files.createDirectories(path.getParent());
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            SneakyThrow.sneakyThrow(e);
        }
    }

//#if MC < 12005
//$$     @SuppressWarnings("ConstantConditions")
//$$     private static void encodeGamePacket(FriendlyByteBuf buf, Packet<?> packet) {
//$$         int id = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
//$$         buf.writeVarInt(id);
//$$         packet.write(buf);
//$$     }
//#endif

//#if MC >= 12002
    public void writeConfigurationPackets(StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configurationPacketCodec,
                                          List<Packet<? super ClientConfigurationPacketListener>> packets) {
        List<Packet<? super ClientConfigurationPacketListener>> packetCopy = new ArrayList<>(packets);
        this.submit(writer -> {
            for (Packet<? super ClientConfigurationPacketListener> packet : packetCopy) {
                writer.startAction(ActionConfigurationPacket.INSTANCE);
                configurationPacketCodec.encode(writer.friendlyByteBuf(), packet);
                writer.finishAction(ActionConfigurationPacket.INSTANCE);
            }
        });
    }
//#endif

    public void writeReplayChunk(String chunkName, String metadata) {
        this.submit(writer -> {
            try {
                Path chunkFile = this.recordFolder.resolve(chunkName);
                Files.write(chunkFile, writer.popBytes());

                Path metaFile = this.recordFolder.resolve("metadata.json");
                if (Files.exists(metaFile)) {
                    Files.move(metaFile, this.recordFolder.resolve("metadata.json.old"),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(metaFile, metadata);
            } catch (IOException e) {
                SneakyThrow.sneakyThrow(e);
            }
        });
    }

    public Path finish() {
        this.waitForTasks();
        this.shouldStop.set(true);
        while (!this.hasStopped.get()) {
            checkForError();
            LockSupport.parkNanos("waiting for async replay writer to stop", 100000L);
        }
        checkForError();
        return this.recordFolder;
    }

    private void waitForTasks() {
        checkForError();
        if (this.hasStopped.get()) {
            throw new IllegalStateException("Cannot wait for tasks on AsyncReplaySaver that has already stopped");
        }
        while (!this.tasks.isEmpty()) {
            checkForError();
            LockSupport.parkNanos("waiting for async replay writer to finish tasks", 100000L);
        }
    }

    private void checkForError() {
        Throwable t = error.get();
        if (t != null) {
            SneakyThrow.sneakyThrow(t);
        }
    }
}
