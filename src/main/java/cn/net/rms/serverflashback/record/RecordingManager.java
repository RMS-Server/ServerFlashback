package cn.net.rms.serverflashback.record;

import cn.net.rms.serverflashback.io.ReplayExporter;
import net.minecraft.core.BlockPos;
//#if MC >= 11900
import net.minecraft.core.Holder;
//#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RecordingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverflashback");
    private static final RecordingManager INSTANCE = new RecordingManager();

    private final Map<String, ServerRecorder> activeRecordings = new ConcurrentHashMap<>();

    private RecordingManager() {}

    public static RecordingManager getInstance() {
        return INSTANCE;
    }

    public String startRecording(MinecraftServer server, ServerLevel level, BlockPos center, int radius, String name) {
        if (name == null || name.isEmpty()) {
            name = LocalDateTime.now().withNano(0).toString();
        }
        if (activeRecordings.containsKey(name)) {
            return null;
        }

        ServerRecorder recorder = new ServerRecorder(server, level, center, radius, name);
        activeRecordings.put(name, recorder);
        LOGGER.info("Started recording '{}' at ({}, {}, {}) radius={} in {}",
                name, center.getX(), center.getY(), center.getZ(), radius, level.dimension().location());
        return name;
    }

    public boolean stopRecording(MinecraftServer server, String name) {
        ServerRecorder recorder = activeRecordings.remove(name);
        if (recorder == null) return false;

        recorder.endTick(true);
        Path recordFolder = recorder.finish();

//#if MC >= 12005
        Path replayDir = server.getServerDirectory().resolve("serverflashback").resolve("replays");
//#else
//$$         Path replayDir = server.getServerDirectory().toPath().resolve("serverflashback").resolve("replays");
//#endif
        try {
            Files.createDirectories(replayDir);
        } catch (Exception e) {
            LOGGER.error("Failed to create replay directory", e);
        }

        String filename = name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".zip";
        Path outputFile = replayDir.resolve(filename);
        ReplayExporter.export(recordFolder, outputFile, name);
        LOGGER.info("Stopped recording '{}', exported to {}", name, outputFile);
        return true;
    }

    public boolean pauseRecording(String name) {
        ServerRecorder recorder = activeRecordings.get(name);
        if (recorder == null) return false;
        recorder.setPaused(true);
        return true;
    }

    public boolean resumeRecording(String name) {
        ServerRecorder recorder = activeRecordings.get(name);
        if (recorder == null) return false;
        recorder.setPaused(false);
        return true;
    }

    public boolean addMarker(String name, ReplayMarker marker) {
        ServerRecorder recorder = activeRecordings.get(name);
        if (recorder == null) return false;
        recorder.addMarker(marker);
        return true;
    }

    public Set<String> getActiveRecordingNames() {
        return Collections.unmodifiableSet(activeRecordings.keySet());
    }

    public ServerRecorder getRecorder(String name) {
        return activeRecordings.get(name);
    }

    public void onServerTick(MinecraftServer server) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            try {
                recorder.endTick(false);
            } catch (Exception e) {
                LOGGER.error("Error during recording tick", e);
            }
        }
    }

    public void onBlockChange(ServerLevel level, BlockPos pos, BlockState state) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.isInArea(pos, level.dimension())) {
                recorder.queueBlockChange(pos, state);
            }
        }
    }

    public void onLevelEvent(ServerLevel level, int type, BlockPos pos, int data, boolean global) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.isInArea(pos, level.dimension())) {
                recorder.queueLevelEvent(type, pos, data, global);
            }
        }
    }

//#if MC >= 11900
    public void onSound(ServerLevel level, Holder<SoundEvent> sound, SoundSource source,
                        double x, double y, double z, float volume, float pitch, long seed) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.getDimension() == level.dimension() && recorder.isInArea(x, z)) {
                recorder.queueSound(sound, source, x, y, z, volume, pitch, seed);
            }
        }
    }

    public void onEntitySound(ServerLevel level, Holder<SoundEvent> sound, SoundSource source,
                              Entity entity, float volume, float pitch, long seed) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.getDimension() == level.dimension()) {
                recorder.queueEntitySound(sound, source, entity, volume, pitch, seed);
            }
        }
    }
//#else
//$$     public void onSound(ServerLevel level, SoundEvent sound, SoundSource source,
//$$                         double x, double y, double z, float volume, float pitch) {
//$$         for (ServerRecorder recorder : activeRecordings.values()) {
//$$             if (recorder.getDimension() == level.dimension() && recorder.isInArea(x, z)) {
//$$                 recorder.queueSound(sound, source, x, y, z, volume, pitch);
//$$             }
//$$         }
//$$     }
//#endif

    public void onEntitySpawn(ServerLevel level, Entity entity) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.getDimension() == level.dimension()
                    && recorder.isInArea(entity.getX(), entity.getZ())) {
                recorder.queueEntitySpawn(entity);
            }
        }
    }

    public void onEntityDespawn(ServerLevel level, Entity entity) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.getDimension() == level.dimension()) {
                recorder.queueEntityDespawn(entity.getId());
            }
        }
    }

    public void onEntityEvent(ServerLevel level, Entity entity, byte status) {
        onEntityPacket(level, entity, new ClientboundEntityEventPacket(entity, status));
    }

    public void onEntityPacket(ServerLevel level, Entity entity,
                               Packet<? super ClientGamePacketListener> packet) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.getDimension() == level.dimension()
                    && recorder.isInArea(entity.getX(), entity.getZ())) {
                recorder.queueEntityPacket(packet);
            }
        }
    }

    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            recorder.onChunkUnload(level, chunk);
        }
    }

    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            recorder.onChunkLoad(level, chunk.getPos());
        }
    }

    public void onPositionedGamePacket(ServerLevel level, double x, double z,
                                       Packet<? super ClientGamePacketListener> packet) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.getDimension() == level.dimension() && recorder.isInArea(x, z)) {
                recorder.queueGamePacket(packet);
            }
        }
    }

    public void onBlockEntityChanged(ServerLevel level, BlockPos pos) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            if (recorder.isInArea(pos, level.dimension())) {
                recorder.queueBlockEntityUpdate(level, pos);
            }
        }
    }

    public void onGlobalGamePacket(Packet<? super ClientGamePacketListener> packet) {
        for (ServerRecorder recorder : activeRecordings.values()) {
            recorder.queueGamePacket(packet);
        }
    }

    public void stopAll(MinecraftServer server) {
        for (String name : new ArrayList<>(activeRecordings.keySet())) {
            stopRecording(server, name);
        }
    }

    public boolean hasActiveRecordings() {
        return !activeRecordings.isEmpty();
    }
}
