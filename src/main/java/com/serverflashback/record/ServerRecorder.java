package com.serverflashback.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import com.serverflashback.action.*;
import com.serverflashback.chunk.ChunkDataCache;
import com.serverflashback.chunk.ChunkForceLoader;
import com.serverflashback.io.AsyncReplaySaver;
import com.serverflashback.io.ReplayWriter;
import com.serverflashback.util.PacketHelper;
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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
//#if MC >= 12002
import net.minecraft.tags.TagNetworkSerialization;
//#endif
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
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

    private final BlockPos center;
    private final int radiusInBlocks;
    private final int radiusInChunks;
    private final ResourceKey<Level> dimension;
    private final MinecraftServer server;
    private final RegistryAccess registryAccess;

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

    private final UUID virtualPlayerUUID = UUID.randomUUID();
    private static final int VIRTUAL_PLAYER_ID = Integer.MAX_VALUE - 1;

    private volatile boolean isPaused = false;
    private volatile boolean wasPaused = false;
    private volatile boolean closeForWriting = false;
    private volatile boolean needsInitialSnapshot = true;
    private boolean finishedPausing = false;

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
//#else
//$$ public void queueSound(SoundEvent sound, SoundSource source,
//$$                        double x, double y, double z, float volume, float pitch) {
//$$     if (!closeForWriting && !isPaused) {
//$$         pendingGamePackets.add(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch));
//$$     }
//$$ }
//#endif

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

    public void onChunkLoad(ServerLevel level, ChunkPos pos) {
        if (isChunkInArea(pos) && level.dimension() == this.dimension) {
            chunkDataCache.onChunkLoad(pos);
        }
    }

    public void endTick(boolean close) {
        if (this.closeForWriting) return;
        if (close) this.closeForWriting = true;

        if (this.isPaused) this.wasPaused = true;

        ServerLevel level = server.getLevel(this.dimension);
        if (level == null) return;

        if (this.needsInitialSnapshot) {
            this.needsInitialSnapshot = false;
            this.forceLoadAndCacheChunks(level);
            this.writeSnapshot(level, true);
        }

        this.flushPendingPackets();

        boolean wroteNewTick = false;
        if (!this.isPaused) {
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

    private void forceLoadAndCacheChunks(ServerLevel level) {
        ChunkPos cc = new ChunkPos(center);
        var packets = ChunkForceLoader.forceLoadAndCreatePackets(level, cc, radiusInChunks);
        for (var entry : packets.entrySet()) {
            chunkDataCache.cacheChunkPacket(entry.getKey(), entry.getValue());
        }
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

        for (Entity entity : level.getAllEntities()) {
            if (PacketHelper.shouldIgnoreEntity(entity)) continue;
            if (!isInArea(entity.getX(), entity.getZ())) continue;

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

        // Entities
        for (Entity entity : level.getAllEntities()) {
            if (PacketHelper.shouldIgnoreEntity(entity)) continue;
            if (!isInArea(entity.getX(), entity.getZ())) continue;

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

    public Path finish() {
        return asyncReplaySaver.finish();
    }

    public String getDebugString() {
        return String.format("[ServerFlashback] '%s' T:%d S:%d(%d/%d)",
                metadata.worldName, writtenTicks, metadata.chunks.size(),
                writtenTicksInChunk, CHUNK_LENGTH_SECONDS * 20);
    }
}
