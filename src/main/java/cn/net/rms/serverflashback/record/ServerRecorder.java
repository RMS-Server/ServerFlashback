package cn.net.rms.serverflashback.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import cn.net.rms.serverflashback.action.*;
import cn.net.rms.serverflashback.chunk.ChunkDataCache;
import cn.net.rms.serverflashback.chunk.ChunkForceLoader;
import cn.net.rms.serverflashback.io.AsyncReplaySaver;
import cn.net.rms.serverflashback.io.ReplayWriter;
import cn.net.rms.serverflashback.util.PacketHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
//#if MC >= 12005
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
//#else
//$$ import net.minecraft.network.FriendlyByteBuf;
//#endif
import net.minecraft.network.protocol.Packet;
//#if MC >= 12002
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
//#endif
//#if MC >= 12005
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
//#endif
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
//#if MC >= 12002
import net.minecraft.resources.RegistryOps;
//#endif
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
//#if MC >= 12002
import net.minecraft.tags.TagNetworkSerialization;
//#endif
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
//#if MC >= 12005
import net.minecraft.world.entity.Leashable;
//#else
//$$ import net.minecraft.world.entity.Mob;
//#endif
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
//#if MC >= 12002
import net.minecraft.world.flag.FeatureFlags;
//#endif
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
//#if MC >= 12005
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.network.chat.numbers.NumberFormat;
//#else
//$$ import net.minecraft.world.scores.Score;
//$$ import net.minecraft.server.ServerScoreboard;
//#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ServerRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverflashback");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int CHUNK_LENGTH_SECONDS = 5 * 60;

    private final AsyncReplaySaver asyncReplaySaver;
    private final ChunkDataCache chunkDataCache;
//#if MC >= 12005
    private final StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configurationPacketCodec;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec;
//#endif

    private BlockPos center;
    private int radiusInBlocks;
    private int radiusInChunks;
    private ResourceKey<Level> dimension;
    private final MinecraftServer server;
    private final RegistryAccess registryAccess;

    private UUID followedPlayerUuid;
    private ChunkPos lastPlayerChunkPos;
    private boolean followAutoPaused;

    private int writtenTicksInChunk = 0;
    private int writtenTicks = 0;
    private final FlashbackMeta metadata = new FlashbackMeta();

    private final Queue<Packet<? super ClientGamePacketListener>> pendingGamePackets = new ConcurrentLinkedQueue<>();

    private record Position(double x, double y, double z, float yaw, float pitch, float headYRot, boolean onGround) {
        public Position {
            yaw = Mth.wrapDegrees(yaw);
            pitch = Mth.wrapDegrees(pitch);
            headYRot = Mth.wrapDegrees(headYRot);
        }
    }

    private final Map<Integer, Position> lastPositions = new HashMap<>();
    private final Set<Integer> trackedEntityIds = new HashSet<>();
    private final Set<ChunkPos> pendingRetryChunks = new HashSet<>();

    private static final long BLOCK_ENTITY_COOLDOWN_TICKS = 20;
    private final Map<Long, Long> blockEntityUpdateTicks = new HashMap<>();

    private final UUID virtualPlayerUUID = UUID.randomUUID();
    private static final int VIRTUAL_PLAYER_ID = Integer.MAX_VALUE - 1;

    private volatile boolean isPaused = false;
    private volatile boolean wasPaused = false;
    private volatile boolean closeForWriting = false;
    private volatile boolean needsInitialSnapshot = true;
    private boolean finishedPausing = false;
    // Set when a dimension change occurs so the next chunk's snapshot is force-played by the client.
    private boolean pendingForceSnapshot = false;

    // Chunk loading is split into two stages to avoid a single-tick ticket registration spike.
    // toSchedule: positions not yet ticketed (drained TICKET_BATCH_PER_TICK per tick)
    // pendingChunkLoads: positions with ticket added, waiting for the chunk to load
    private static final int TICKET_BATCH_PER_TICK = 500;
    private List<ChunkPos> toSchedule = null;
    private Set<ChunkPos> pendingChunkLoads = null;

    private boolean lastRaining = false;
    private float lastRainLevel = 0;
    private float lastThunderLevel = 0;
    private double lastBorderSize = -1;
    private double lastBorderCenterX = Double.NaN;
    private double lastBorderCenterZ = Double.NaN;

    public ServerRecorder(MinecraftServer server, ServerLevel level, BlockPos center, int radiusInBlocks, String name) {
        this.server = server;
        this.registryAccess = server.registryAccess();
        this.center = center;
        this.radiusInBlocks = radiusInBlocks;
        this.radiusInChunks = (radiusInBlocks >> 4) + 1;
        this.dimension = level.dimension();

//#if MC >= 12005
        this.asyncReplaySaver = new AsyncReplaySaver(registryAccess, server.getServerDirectory());
//#else
//$$         this.asyncReplaySaver = new AsyncReplaySaver(server.getServerDirectory().toPath());
//#endif
        this.chunkDataCache = new ChunkDataCache();

//#if MC >= 12005
        this.configurationPacketCodec = ConfigurationProtocols.CLIENTBOUND.codec();
        this.gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                .bind(RegistryFriendlyByteBuf.decorator(registryAccess)).codec();
//#endif

//#if MC >= 12005
        this.metadata.dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
//#else
//$$ this.metadata.dataVersion = SharedConstants.getCurrentVersion().getWorldVersion();
//#endif
        this.metadata.protocolVersion = SharedConstants.getProtocolVersion();
        this.metadata.versionString = SharedConstants.getCurrentVersion().getName();
        this.metadata.worldName = name;
    }

    public static ServerRecorder createFollowRecorder(MinecraftServer server, ServerPlayer player, String name) {
//#if MC >= 12005
        ServerLevel level = player.serverLevel();
//#else
//$$ ServerLevel level = (ServerLevel) player.level;
//#endif
        int viewDist = server.getPlayerList().getViewDistance();
        ServerRecorder recorder = new ServerRecorder(server, level, player.blockPosition(), viewDist * 16, name);
        recorder.followedPlayerUuid = player.getUUID();
        recorder.lastPlayerChunkPos = new ChunkPos(player.blockPosition());
        return recorder;
    }

    public boolean isInArea(BlockPos pos, ResourceKey<Level> dim) {
        if (dim != this.dimension) return false;
        return Math.abs(pos.getX() - center.getX()) <= radiusInBlocks
                && Math.abs(pos.getZ() - center.getZ()) <= radiusInBlocks;
    }

    public boolean isInArea(double x, double z) {
        return Math.abs(x - center.getX()) <= radiusInBlocks
                && Math.abs(z - center.getZ()) <= radiusInBlocks;
    }

    public boolean isChunkInArea(ChunkPos chunkPos) {
        ChunkPos cc = new ChunkPos(center);
        return Math.abs(chunkPos.x - cc.x) <= radiusInChunks
                && Math.abs(chunkPos.z - cc.z) <= radiusInChunks;
    }

    public ResourceKey<Level> getDimension() { return dimension; }
    public boolean isPaused() { return isPaused; }
    public void setPaused(boolean paused) { this.isPaused = paused; }
    public int getWrittenTicks() { return writtenTicks; }
    public BlockPos getCenter() { return center; }
    public int getRadiusInBlocks() { return radiusInBlocks; }
    public UUID getFollowedPlayerUuid() { return followedPlayerUuid; }

    public void addMarker(ReplayMarker marker) {
        this.metadata.replayMarkers.put(this.writtenTicks, marker);
    }

    public void queueBlockChange(BlockPos pos, BlockState state) {
        if (!closeForWriting && !isPaused) {
            pendingGamePackets.add(new ClientboundBlockUpdatePacket(pos, state));
        }
    }

    public void queueLevelEvent(int type, BlockPos blockPos, int data, boolean globalEvent) {
        if (!closeForWriting && !isPaused) {
            pendingGamePackets.add(new ClientboundLevelEventPacket(type, blockPos, data, globalEvent));
        }
    }

//#if MC >= 11900
    public void queueSound(Holder<SoundEvent> sound, SoundSource source,
                           double x, double y, double z, float volume, float pitch, long seed) {
        if (!closeForWriting && !isPaused) {
            pendingGamePackets.add(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed));
        }
    }

    public void queueEntitySound(Holder<SoundEvent> sound, SoundSource source, Entity entity,
                                 float volume, float pitch, long seed) {
        if (!closeForWriting && !isPaused && isInArea(entity.getX(), entity.getZ())) {
            pendingGamePackets.add(new ClientboundSoundEntityPacket(sound, source, entity, volume, pitch, seed));
        }
    }
//#else
//$$ public void queueSound(SoundEvent sound, SoundSource source,
//$$                        double x, double y, double z, float volume, float pitch) {
//$$     if (!closeForWriting && !isPaused) {
//$$         pendingGamePackets.add(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch));
//$$     }
//$$ }
//#endif

    public void queueEntitySpawn(Entity entity) {
        if (closeForWriting || isPaused) return;
        if (PacketHelper.shouldIgnoreEntity(entity)) return;

        trackedEntityIds.add(entity.getId());
        pendingGamePackets.add(PacketHelper.createAddEntity(entity));

//#if MC >= 11904
        List<SynchedEntityData.DataValue<?>> nonDefault = entity.getEntityData().getNonDefaultValues();
        if (nonDefault != null && !nonDefault.isEmpty()) {
            pendingGamePackets.add(new ClientboundSetEntityDataPacket(entity.getId(), nonDefault));
        }
//#else
//$$ List<SynchedEntityData.DataItem<?>> allData = entity.getEntityData().getAll();
//$$ if (allData != null && !allData.isEmpty()) {
//$$     pendingGamePackets.add(new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData(), false));
//$$ }
//#endif

        if (entity instanceof LivingEntity living) {
            Collection<AttributeInstance> syncable = living.getAttributes().getSyncableAttributes();
            if (!syncable.isEmpty()) {
                pendingGamePackets.add(new ClientboundUpdateAttributesPacket(entity.getId(), syncable));
            }

            List<Pair<EquipmentSlot, ItemStack>> equip = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack item = living.getItemBySlot(slot);
                if (!item.isEmpty()) equip.add(Pair.of(slot, item.copy()));
            }
            if (!equip.isEmpty()) {
                pendingGamePackets.add(new ClientboundSetEquipmentPacket(entity.getId(), equip));
            }
        }

        if (entity.isVehicle()) pendingGamePackets.add(new ClientboundSetPassengersPacket(entity));
        if (entity.isPassenger()) pendingGamePackets.add(new ClientboundSetPassengersPacket(entity.getVehicle()));
//#if MC >= 12005
        if (entity instanceof Leashable leashable && leashable.isLeashed()) {
            pendingGamePackets.add(new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
        }
//#else
//$$         if (entity instanceof Mob mob && mob.isLeashed()) {
//$$             pendingGamePackets.add(new ClientboundSetEntityLinkPacket(entity, mob.getLeashHolder()));
//$$         }
//#endif
    }

    public void queueEntityDespawn(int entityId) {
        if (closeForWriting || isPaused) return;
        if (!trackedEntityIds.remove(entityId)) return;
        lastPositions.remove(entityId);
        pendingGamePackets.add(new ClientboundRemoveEntitiesPacket(entityId));
    }

    public void queueEntityPacket(Packet<? super ClientGamePacketListener> packet) {
        if (!closeForWriting && !isPaused) {
            pendingGamePackets.add(packet);
        }
    }

    public void queueGamePacket(Packet<? super ClientGamePacketListener> packet) {
        if (!closeForWriting && !isPaused) {
            pendingGamePackets.add(packet);
        }
    }

    public void queueBlockEntityUpdate(ServerLevel level, BlockPos pos) {
        if (closeForWriting || isPaused) return;

        long packedPos = pos.asLong();
        long currentTick = level.getGameTime();
        Long lastUpdate = blockEntityUpdateTicks.get(packedPos);
        if (lastUpdate != null && currentTick - lastUpdate < BLOCK_ENTITY_COOLDOWN_TICKS) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;

        var packet = be.getUpdatePacket();
        if (packet == null) return;

        blockEntityUpdateTicks.put(packedPos, currentTick);
        pendingGamePackets.add(packet);
    }

    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (isChunkInArea(pos) && level.dimension() == this.dimension) {
            try {
//#if MC >= 11800
                ClientboundLevelChunkWithLightPacket packet =
                        new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
//#else
//$$ ClientboundLevelChunkPacket packet = new ClientboundLevelChunkPacket(chunk);
//#endif
                chunkDataCache.cacheChunkPacket(pos, packet);
            } catch (Exception e) {
                LOGGER.warn("Failed to cache chunk on unload: {}", pos, e);
            }
        }
    }

    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (!isChunkInArea(pos) || level.dimension() != this.dimension) return;
        chunkDataCache.onChunkLoad(pos);

        if (toSchedule != null || closeForWriting || isPaused) return;
        pendingRetryChunks.remove(pos);
        sendChunkPacket(level, chunk, pos);
    }

    public void endTick(boolean close) {
        if (this.closeForWriting) return;

        if (this.followedPlayerUuid != null && !updateFollowedPlayerTracking()) {
            return;
        }

        ServerLevel level = server.getLevel(this.dimension);
        if (level == null) return;

        if (this.needsInitialSnapshot) {
            this.needsInitialSnapshot = false;
            this.toSchedule = ChunkForceLoader.scan(level, new ChunkPos(center), radiusInChunks, chunkDataCache);
            this.pendingChunkLoads = new LinkedHashSet<>();
        }

        if (this.toSchedule != null) {
            if (!this.toSchedule.isEmpty() && !close) {
                ChunkForceLoader.addTicketBatch(level, toSchedule, pendingChunkLoads, TICKET_BATCH_PER_TICK);
            }

            if (close && (!toSchedule.isEmpty() || !pendingChunkLoads.isEmpty())) {
                ChunkForceLoader.addAndForceLoadRemaining(level, toSchedule, pendingChunkLoads, chunkDataCache);
            } else if (!toSchedule.isEmpty() || !pendingChunkLoads.isEmpty()) {
                ChunkForceLoader.checkProgress(level, pendingChunkLoads, chunkDataCache);
                if (!toSchedule.isEmpty() || !pendingChunkLoads.isEmpty()) return;
            }

            this.completeInitialSnapshot(level);
        }

        if (close) this.closeForWriting = true;
        if (this.isPaused) this.wasPaused = true;

        this.retryPendingChunks(level);
        this.flushPendingPackets();

        boolean wroteNewTick = false;
        if (!this.isPaused) {
            this.trackWeatherChanges(level);
            if (this.writtenTicks % 20 == 0) {
                this.sendTimePacket(level);
                this.trackBorderChanges(level);
            }
            this.writeEntityPositions(level);
            wroteNewTick = true;
            this.asyncReplaySaver.submit(writer -> writer.startAndFinishAction(ActionNextTick.INSTANCE));
            this.writtenTicksInChunk += 1;
            this.writtenTicks += 1;
        }

        this.finishedPausing |= this.wasPaused && !this.isPaused;

        boolean writeChunk = close;
        writeChunk |= this.writtenTicksInChunk >= CHUNK_LENGTH_SECONDS * 20 || this.finishedPausing;

        if (writeChunk && this.writtenTicksInChunk > 0) {
            if (!wroteNewTick) {
                this.asyncReplaySaver.submit(writer -> writer.startAndFinishAction(ActionNextTick.INSTANCE));
                this.writtenTicksInChunk += 1;
                this.writtenTicks += 1;
            }

            int chunkId = this.metadata.chunks.size();
            String chunkName = "c" + chunkId + ".flashback";

            var chunkMeta = new FlashbackChunkMeta();
            chunkMeta.duration = this.writtenTicksInChunk;
            if (this.pendingForceSnapshot) {
                chunkMeta.forcePlaySnapshot = true;
                this.pendingForceSnapshot = false;
            }
            this.metadata.chunks.put(chunkName, chunkMeta);
            this.metadata.totalTicks = this.writtenTicks;

            this.asyncReplaySaver.writeReplayChunk(chunkName, GSON.toJson(this.metadata.toJson()));
            this.writtenTicksInChunk = 0;

            if (!close) {
                if (this.finishedPausing) {
                    this.asyncReplaySaver.submit(ReplayWriter::startSnapshot);
                    this.asyncReplaySaver.submit(ReplayWriter::endSnapshot);
                    this.writeSnapshot(level, false);
                } else {
                    this.writeSnapshot(level, true);
                }
            }
            this.finishedPausing = false;
        }

        if (!this.isPaused) this.wasPaused = false;
    }

    private boolean updateFollowedPlayerTracking() {
        ServerPlayer player = server.getPlayerList().getPlayer(followedPlayerUuid);
        if (player == null) {
            if (!isPaused) {
                setPaused(true);
                followAutoPaused = true;
            }
            return false;
        }

        if (followAutoPaused) {
            setPaused(false);
            this.wasPaused = true;
            followAutoPaused = false;
        }

//#if MC >= 12005
        ServerLevel playerLevel = player.serverLevel();
//#else
//$$ ServerLevel playerLevel = (ServerLevel) player.level;
//#endif
        ResourceKey<Level> playerDim = playerLevel.dimension();
        BlockPos playerPos = player.blockPosition();
        ChunkPos playerChunk = new ChunkPos(playerPos);
        int viewDist = server.getPlayerList().getViewDistance();
        int newRadiusInBlocks = viewDist * 16;
        int newRadiusInChunks = (newRadiusInBlocks >> 4) + 1;

        if (playerDim != this.dimension) {
            if (this.writtenTicks > 0) {
                flushCurrentReplayChunk();
            }

            ServerLevel oldLevel = server.getLevel(this.dimension);
            if (oldLevel != null && this.pendingChunkLoads != null && !this.pendingChunkLoads.isEmpty()) {
                ChunkForceLoader.removeTickets(oldLevel, this.pendingChunkLoads);
            }
            this.toSchedule = null;
            this.pendingChunkLoads = null;

            this.dimension = playerDim;
            this.needsInitialSnapshot = true;
            // The next chunk's snapshot switches dimensions; client must play it during linear playback.
            this.pendingForceSnapshot = true;
            this.trackedEntityIds.clear();
            this.lastPositions.clear();
            this.blockEntityUpdateTicks.clear();
            this.pendingGamePackets.clear();
            this.pendingRetryChunks.clear();
            this.chunkDataCache.clear();
            this.wasPaused = false;
            this.finishedPausing = false;
        } else if (lastPlayerChunkPos != null && !playerChunk.equals(lastPlayerChunkPos)) {
            updateDynamicChunks(lastPlayerChunkPos, playerChunk, radiusInChunks, newRadiusInChunks, playerLevel);
        }

        this.center = playerPos;
        this.radiusInBlocks = newRadiusInBlocks;
        this.radiusInChunks = newRadiusInChunks;
        this.lastPlayerChunkPos = playerChunk;
        return true;
    }

    private void updateDynamicChunks(ChunkPos oldCenter, ChunkPos newCenter,
                                     int oldRadius, int newRadius, ServerLevel level) {
        Set<ChunkPos> oldChunks = new HashSet<>();
        for (int dx = -oldRadius; dx <= oldRadius; dx++) {
            for (int dz = -oldRadius; dz <= oldRadius; dz++) {
                oldChunks.add(new ChunkPos(oldCenter.x + dx, oldCenter.z + dz));
            }
        }
        Set<ChunkPos> newChunks = new HashSet<>();
        for (int dx = -newRadius; dx <= newRadius; dx++) {
            for (int dz = -newRadius; dz <= newRadius; dz++) {
                newChunks.add(new ChunkPos(newCenter.x + dx, newCenter.z + dz));
            }
        }

        // ClientboundForgetLevelChunkPacket is not supported by ReplayGamePacketHandler (throws UnsupportedPacketException),
        // so old out-of-range chunks are simply left in the replay world rather than explicitly unloaded.

        for (ChunkPos cp : newChunks) {
            if (!oldChunks.contains(cp)) {
                LevelChunk loaded = level.getChunkSource().getChunkNow(cp.x, cp.z);
                if (loaded != null) {
                    sendChunkPacket(level, loaded, cp);
                } else {
                    pendingRetryChunks.add(cp);
                }
            }
        }
    }

    private void sendChunkPacket(ServerLevel level, LevelChunk chunk, ChunkPos cp) {
        try {
//#if MC >= 11800
            ClientboundLevelChunkWithLightPacket pkt =
                    new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
//#else
//$$ ClientboundLevelChunkPacket pkt = new ClientboundLevelChunkPacket(chunk);
//#endif
            pendingGamePackets.add(pkt);
            chunkDataCache.cacheChunkPacket(cp, pkt);
        } catch (Exception e) {
            LOGGER.warn("Failed to create chunk packet: {}", cp, e);
        }
    }

    private void retryPendingChunks(ServerLevel level) {
        if (pendingRetryChunks.isEmpty()) return;
        Iterator<ChunkPos> it = pendingRetryChunks.iterator();
        while (it.hasNext()) {
            ChunkPos cp = it.next();
            if (!isChunkInArea(cp)) {
                it.remove();
                continue;
            }
            LevelChunk loaded = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (loaded != null) {
                sendChunkPacket(level, loaded, cp);
                it.remove();
            }
        }
    }

    private void flushCurrentReplayChunk() {
        if (this.writtenTicksInChunk == 0) {
            this.asyncReplaySaver.submit(writer -> writer.startAndFinishAction(ActionNextTick.INSTANCE));
            this.writtenTicksInChunk = 1;
            this.writtenTicks += 1;
        }
        int chunkId = this.metadata.chunks.size();
        String chunkName = "c" + chunkId + ".flashback";
        var chunkMeta = new FlashbackChunkMeta();
        chunkMeta.duration = this.writtenTicksInChunk;
        if (this.pendingForceSnapshot) {
            chunkMeta.forcePlaySnapshot = true;
            this.pendingForceSnapshot = false;
        }
        this.metadata.chunks.put(chunkName, chunkMeta);
        this.metadata.totalTicks = this.writtenTicks;
        this.asyncReplaySaver.writeReplayChunk(chunkName, GSON.toJson(this.metadata.toJson()));
        this.writtenTicksInChunk = 0;
    }

    private void completeInitialSnapshot(ServerLevel level) {
        // pendingChunkLoads should be empty here; clean up any stragglers defensively.
        if (pendingChunkLoads != null && !pendingChunkLoads.isEmpty()) {
            ChunkForceLoader.removeTickets(level, pendingChunkLoads);
        }
        this.toSchedule = null;
        this.pendingChunkLoads = null;

        ChunkForceLoader.repairMissingChunks(level, new ChunkPos(center), radiusInChunks, chunkDataCache);

        pendingGamePackets.clear();
        pendingRetryChunks.clear();

        this.writeSnapshot(level, true);

        this.lastRaining = level.isRaining();
        this.lastRainLevel = level.getRainLevel(1.0f);
        this.lastThunderLevel = level.getThunderLevel(1.0f);
        WorldBorder border = level.getWorldBorder();
        this.lastBorderSize = border.getSize();
        this.lastBorderCenterX = border.getCenterX();
        this.lastBorderCenterZ = border.getCenterZ();
    }

    private void flushPendingPackets() {
        List<Packet<? super ClientGamePacketListener>> batch = new ArrayList<>();
        Packet<? super ClientGamePacketListener> p;
        while ((p = pendingGamePackets.poll()) != null) batch.add(p);
        if (!batch.isEmpty()) {
//#if MC >= 12005
            asyncReplaySaver.writeGamePackets(gamePacketCodec, batch);
//#else
//$$             asyncReplaySaver.writeGamePackets(batch);
//#endif
        }
    }

    private void writeEntityPositions(ServerLevel level) {
        record IdPos(int id, Position pos) {}
        List<IdPos> changed = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();

        for (Entity entity : level.getAllEntities()) {
            if (PacketHelper.shouldIgnoreEntity(entity)) continue;
            if (!isInArea(entity.getX(), entity.getZ())) continue;

            seenIds.add(entity.getId());

            float headRot = entity.getYHeadRot();
            if (entity instanceof LivingEntity living) {
                headRot = living.yHeadRot;
            }

            Position pos = new Position(entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYRot(), entity.getXRot(), headRot, entity.onGround());

            Position last = lastPositions.get(entity.getId());
            if (!Objects.equals(pos, last)) {
                lastPositions.put(entity.getId(), pos);
                changed.add(new IdPos(entity.getId(), pos));
            }
        }

        List<Integer> leftArea = new ArrayList<>();
        for (int id : trackedEntityIds) {
            if (!seenIds.contains(id)) {
                leftArea.add(id);
            }
        }
        for (int id : leftArea) {
            trackedEntityIds.remove(id);
            lastPositions.remove(id);
            pendingGamePackets.add(new ClientboundRemoveEntitiesPacket(id));
        }

        if (changed.isEmpty()) return;

        ResourceKey<Level> dim = this.dimension;
        asyncReplaySaver.submit(writer -> {
            writer.startAction(ActionMoveEntities.INSTANCE);
//#if MC >= 12005
            RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
//#else
//$$             FriendlyByteBuf buf = writer.friendlyByteBuf();
//#endif
            buf.writeVarInt(1);
//#if MC >= 11800
            buf.writeResourceKey(dim);
//#else
//$$ buf.writeResourceLocation(dim.location());
//#endif
            buf.writeVarInt(changed.size());
            for (IdPos cp : changed) {
                buf.writeVarInt(cp.id);
                buf.writeDouble(cp.pos.x);
                buf.writeDouble(cp.pos.y);
                buf.writeDouble(cp.pos.z);
                buf.writeFloat(cp.pos.yaw);
                buf.writeFloat(cp.pos.pitch);
                buf.writeFloat(cp.pos.headYRot);
                buf.writeBoolean(cp.pos.onGround);
            }
            writer.finishAction(ActionMoveEntities.INSTANCE);
        });
    }

    private void writeCreateLocalPlayer() {
        UUID uuid = this.virtualPlayerUUID;
        double x = center.getX() + 0.5;
        double y = center.getY();
        double z = center.getZ() + 0.5;
        GameProfile profile = new GameProfile(uuid, "ServerFlashback");

        asyncReplaySaver.submit(writer -> {
            writer.startAction(ActionCreateLocalPlayer.INSTANCE);
//#if MC >= 12005
            RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
//#else
//$$             FriendlyByteBuf buf = writer.friendlyByteBuf();
//#endif
            buf.writeUUID(uuid);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(0);
            buf.writeFloat(0);
            buf.writeFloat(0);
//#if MC >= 11900
            buf.writeVec3(Vec3.ZERO);
//#else
//$$ buf.writeDouble(0); buf.writeDouble(0); buf.writeDouble(0);
//#endif
//#if MC >= 12005
            ByteBufCodecs.GAME_PROFILE.encode(buf, profile);
//#else
//$$ buf.writeUUID(profile.getId());
//$$ buf.writeUtf(profile.getName());
//$$ buf.writeVarInt(0);
//#endif
            buf.writeVarInt(GameType.SPECTATOR.getId());
            writer.finishAction(ActionCreateLocalPlayer.INSTANCE);
        });
    }

    public void writeSnapshot(ServerLevel level, boolean asActualSnapshot) {
        blockEntityUpdateTicks.clear();

        if (asActualSnapshot) {
            asyncReplaySaver.submit(ReplayWriter::startSnapshot);
        }

//#if MC >= 12002
        List<Packet<? super ClientConfigurationPacketListener>> cfgPackets = new ArrayList<>();
        cfgPackets.add(new ClientboundUpdateEnabledFeaturesPacket(
                FeatureFlags.REGISTRY.toNames(level.enabledFeatures())));

        RegistryOps<Tag> ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);
        RegistrySynchronization.packRegistries(ops, registryAccess, Set.of(), (key, entries) ->
                cfgPackets.add(new ClientboundRegistryDataPacket(key, entries)));

        cfgPackets.add(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
        asyncReplaySaver.writeConfigurationPackets(configurationPacketCodec, cfgPackets);
//#endif

        List<Packet<? super ClientGamePacketListener>> gamePackets = new ArrayList<>();

        long hashedSeed = level.getSeed();
//#if MC >= 12005
        CommonPlayerSpawnInfo spawnInfo = new CommonPlayerSpawnInfo(
                level.dimensionTypeRegistration(), level.dimension(), hashedSeed,
                GameType.SPECTATOR, null, level.isDebug(), level.isFlat(),
                Optional.empty(), 0, level.getSeaLevel());

        gamePackets.add(new ClientboundLoginPacket(
                VIRTUAL_PLAYER_ID, level.getLevelData().isHardcore(),
                server.levelKeys(), 1, radiusInChunks,
                server.getPlayerList().getViewDistance(),
                false, true, false, spawnInfo, false));
//#else
//$$ gamePackets.add(new ClientboundLoginPacket(
//$$         VIRTUAL_PLAYER_ID, GameType.SPECTATOR, null,
//$$         hashedSeed, level.getLevelData().isHardcore(),
//$$         server.levelKeys(),
//$$         (net.minecraft.core.RegistryAccess.RegistryHolder) server.registryAccess(), level.dimensionType(),
//$$         level.dimension(),
//$$         1, radiusInChunks,
//$$         false, true, level.isDebug(), level.isFlat()));
//#endif

//#if MC >= 12005
        asyncReplaySaver.writeGamePackets(gamePacketCodec, gamePackets);
//#else
//$$         asyncReplaySaver.writeGamePackets(gamePackets);
//#endif
        gamePackets.clear();
        writeCreateLocalPlayer();

        // Player info
//#if MC >= 12002
        gamePackets.add(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(server.getPlayerList().getPlayers()));
//#else
//$$         gamePackets.add(new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.ADD_PLAYER, server.getPlayerList().getPlayers()));
//#endif

        // World state
        WorldBorder border = level.getWorldBorder();
        gamePackets.add(new ClientboundInitializeBorderPacket(border));
        gamePackets.add(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
        gamePackets.add(new ClientboundSetDefaultSpawnPositionPacket(level.getSharedSpawnPos(), level.getSharedSpawnAngle()));

        if (level.isRaining()) {
            gamePackets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0f));
        } else {
            gamePackets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0f));
        }
        gamePackets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0f)));
        gamePackets.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0f)));

        // Tab list
//#if MC >= 11900
        gamePackets.add(new ClientboundTabListPacket(Component.empty(), Component.empty()));
//#else
//$$         gamePackets.add(new ClientboundTabListPacket(net.minecraft.network.chat.TextComponent.EMPTY, net.minecraft.network.chat.TextComponent.EMPTY));
//#endif

        // Boss bars
        for (var event : server.getCustomBossEvents().getEvents()) {
            if (event.isVisible()) {
                gamePackets.add(ClientboundBossEventPacket.createAddPacket(event));
            }
        }

        // Scoreboard
//#if MC >= 12005
        var scoreboard = level.getScoreboard();
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            gamePackets.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }
        Set<Objective> handledObjectives = new HashSet<>();
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null && handledObjectives.add(objective)) {
                gamePackets.add(new ClientboundSetObjectivePacket(objective, 0));
                for (DisplaySlot slot2 : DisplaySlot.values()) {
                    if (scoreboard.getDisplayObjective(slot2) == objective) {
                        gamePackets.add(new ClientboundSetDisplayObjectivePacket(slot2, objective));
                    }
                }
                for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                    gamePackets.add(new ClientboundSetScorePacket(
                            entry.owner(), objective.getName(), entry.value(),
                            Optional.ofNullable(entry.display()),
                            Optional.ofNullable(entry.numberFormatOverride())));
                }
            }
        }
//#else
//$$         var scoreboard = level.getScoreboard();
//$$         for (PlayerTeam team : scoreboard.getPlayerTeams()) {
//$$             gamePackets.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
//$$         }
//$$         Set<Objective> handledObjectives = new HashSet<>();
//$$         for (int slot = 0; slot < 19; slot++) {
//$$             Objective objective = scoreboard.getDisplayObjective(slot);
//$$             if (objective != null && handledObjectives.add(objective)) {
//$$                 gamePackets.add(new ClientboundSetObjectivePacket(objective, 0));
//$$                 for (int slot2 = 0; slot2 < 19; slot2++) {
//$$                     if (scoreboard.getDisplayObjective(slot2) == objective) {
//$$                         gamePackets.add(new ClientboundSetDisplayObjectivePacket(slot2, objective));
//$$                     }
//$$                 }
//$$                 for (Score score : scoreboard.getPlayerScores(objective)) {
//$$                     gamePackets.add(new ClientboundSetScorePacket(
//$$                             ServerScoreboard.Method.CHANGE, objective.getName(),
//$$                             score.getOwner(), score.getScore()));
//$$                 }
//$$             }
//$$         }
//#endif

        // Chunks
        ChunkPos cc = new ChunkPos(center);
        List<ClientboundLevelChunkWithLightPacket> chunkPackets = new ArrayList<>();

        for (int dx = -radiusInChunks; dx <= radiusInChunks; dx++) {
            for (int dz = -radiusInChunks; dz <= radiusInChunks; dz++) {
                ChunkPos cp = new ChunkPos(cc.x + dx, cc.z + dz);
                LevelChunk loaded = level.getChunkSource().getChunkNow(cp.x, cp.z);
                if (loaded != null) {
                    try {
//#if MC >= 11800
                        ClientboundLevelChunkWithLightPacket pkt =
                                new ClientboundLevelChunkWithLightPacket(loaded, level.getLightEngine(), null, null);
//#else
//$$ ClientboundLevelChunkPacket pkt = new ClientboundLevelChunkPacket(loaded);
//#endif
                        chunkPackets.add(pkt);
                        chunkDataCache.cacheChunkPacket(cp, pkt);
                    } catch (Exception e) {
                        ClientboundLevelChunkWithLightPacket cached = chunkDataCache.getCachedPacket(cp);
                        if (cached != null) chunkPackets.add(cached);
                    }
                } else {
                    ClientboundLevelChunkWithLightPacket cached = chunkDataCache.getCachedPacket(cp);
                    if (cached != null) chunkPackets.add(cached);
                }
            }
        }

        chunkPackets.sort(Comparator.comparingInt(p -> {
            int ddx = p.getX() - cc.x;
            int ddz = p.getZ() - cc.z;
            return ddx * ddx + ddz * ddz;
        }));
        gamePackets.addAll(chunkPackets);

        // Entities — snapshot provides a complete fresh set, reset tracking
        trackedEntityIds.clear();
        lastPositions.clear();
        for (Entity entity : level.getAllEntities()) {
            if (PacketHelper.shouldIgnoreEntity(entity)) continue;
            if (!isInArea(entity.getX(), entity.getZ())) continue;

            trackedEntityIds.add(entity.getId());
            gamePackets.add(PacketHelper.createAddEntity(entity));

//#if MC >= 11904
            List<SynchedEntityData.DataValue<?>> nonDefault = entity.getEntityData().getNonDefaultValues();
            if (nonDefault != null && !nonDefault.isEmpty()) {
                gamePackets.add(new ClientboundSetEntityDataPacket(entity.getId(), nonDefault));
            }
//#else
//$$ List<SynchedEntityData.DataItem<?>> allData = entity.getEntityData().getAll();
//$$ if (allData != null && !allData.isEmpty()) {
//$$     gamePackets.add(new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData(), false));
//$$ }
//#endif

            if (entity instanceof LivingEntity living) {
                Collection<AttributeInstance> syncable = living.getAttributes().getSyncableAttributes();
                if (!syncable.isEmpty()) {
                    gamePackets.add(new ClientboundUpdateAttributesPacket(entity.getId(), syncable));
                }

                List<Pair<EquipmentSlot, ItemStack>> equip = new ArrayList<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack item = living.getItemBySlot(slot);
                    if (!item.isEmpty()) equip.add(Pair.of(slot, item.copy()));
                }
                if (!equip.isEmpty()) {
                    gamePackets.add(new ClientboundSetEquipmentPacket(entity.getId(), equip));
                }
            }

            if (entity.isVehicle()) gamePackets.add(new ClientboundSetPassengersPacket(entity));
            if (entity.isPassenger()) gamePackets.add(new ClientboundSetPassengersPacket(entity.getVehicle()));
//#if MC >= 12005
            if (entity instanceof Leashable leashable && leashable.isLeashed()) {
                gamePackets.add(new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
            }
//#else
//$$             if (entity instanceof Mob mob && mob.isLeashed()) {
//$$                 gamePackets.add(new ClientboundSetEntityLinkPacket(entity, mob.getLeashHolder()));
//$$             }
//#endif
        }

//#if MC >= 12005
        asyncReplaySaver.writeGamePackets(gamePacketCodec, gamePackets);
//#else
//$$         asyncReplaySaver.writeGamePackets(gamePackets);
//#endif

        if (asActualSnapshot) {
            asyncReplaySaver.submit(ReplayWriter::endSnapshot);
        }
    }

    private void trackWeatherChanges(ServerLevel level) {
        boolean raining = level.isRaining();
        float rainLevel = level.getRainLevel(1.0f);
        float thunderLevel = level.getThunderLevel(1.0f);

        if (raining != lastRaining) {
            lastRaining = raining;
            pendingGamePackets.add(new ClientboundGameEventPacket(
                    raining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        }
        if (Math.abs(rainLevel - lastRainLevel) > 0.01f) {
            lastRainLevel = rainLevel;
            pendingGamePackets.add(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel));
        }
        if (Math.abs(thunderLevel - lastThunderLevel) > 0.01f) {
            lastThunderLevel = thunderLevel;
            pendingGamePackets.add(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel));
        }
    }

    private void sendTimePacket(ServerLevel level) {
        pendingGamePackets.add(new ClientboundSetTimePacket(
                level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
    }

    private void trackBorderChanges(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        double size = border.getSize();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        if (size != lastBorderSize || centerX != lastBorderCenterX || centerZ != lastBorderCenterZ) {
            lastBorderSize = size;
            lastBorderCenterX = centerX;
            lastBorderCenterZ = centerZ;
            pendingGamePackets.add(new ClientboundInitializeBorderPacket(border));
        }
    }

    public Path finish() {
        return asyncReplaySaver.finish();
    }

    public String getDebugString() {
        String followInfo = "";
        if (followedPlayerUuid != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(followedPlayerUuid);
            String playerName = player != null ? player.getGameProfile().getName() : "offline";
            followInfo = " following=" + playerName;
        }
        int scheduling = toSchedule != null ? toSchedule.size() : 0;
        int loading = pendingChunkLoads != null ? pendingChunkLoads.size() : 0;
        if (scheduling + loading > 0) {
            return String.format("[ServerFlashback] '%s'%s Loading chunks: %d scheduling, %d loading",
                    metadata.worldName, followInfo, scheduling, loading);
        }
        return String.format("[ServerFlashback] '%s'%s T:%d S:%d(%d/%d)",
                metadata.worldName, followInfo, writtenTicks, metadata.chunks.size(),
                writtenTicksInChunk, CHUNK_LENGTH_SECONDS * 20);
    }
}
