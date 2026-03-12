package cn.net.rms.serverflashback.chunk;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ChunkForceLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverflashback");

    /** Tickets use margin=0 → level 33 = FULL, weakest level that yields a LevelChunk. */
    public static final TicketType<ChunkPos> RECORDING_TICKET =
            TicketType.create("serverflashback", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Phase 1: scan all chunks in the recording area.
     * Already-loaded chunks are cached immediately.
     * Returns a mutable list of positions that still need a ticket + loading.
     * No tickets are added here — call addTicketBatch() per tick to spread the load.
     */
    public static List<ChunkPos> scan(
            ServerLevel level, ChunkPos center, int radiusInChunks, ChunkDataCache cache) {

        List<ChunkPos> toSchedule = new ArrayList<>();
        int alreadyLoaded = 0;
        int total = (2 * radiusInChunks + 1) * (2 * radiusInChunks + 1);

        for (int dx = -radiusInChunks; dx <= radiusInChunks; dx++) {
            for (int dz = -radiusInChunks; dz <= radiusInChunks; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk != null) {
                    try {
                        cache.cacheChunkPacket(pos, createPacket(chunk, level));
                        alreadyLoaded++;
                    } catch (Exception e) {
                        LOGGER.warn("Failed to cache loaded chunk {}, queuing for ticket load: {}", pos, e.getMessage());
                        toSchedule.add(pos);
                    }
                } else {
                    toSchedule.add(pos);
                }
            }
        }

        LOGGER.info("Chunk loading: {} already loaded, {} queued for async loading (total: {})",
                alreadyLoaded, toSchedule.size(), total);
        return toSchedule;
    }

    /**
     * Phase 2 (called each tick): pop up to batchSize positions from toSchedule,
     * add a ticket for each, and move them into the pending set.
     */
    public static void addTicketBatch(
            ServerLevel level, List<ChunkPos> toSchedule, Set<ChunkPos> pending, int batchSize) {

        int count = Math.min(batchSize, toSchedule.size());
        for (int i = 0; i < count; i++) {
            ChunkPos pos = toSchedule.remove(toSchedule.size() - 1);
            level.getChunkSource().addRegionTicket(RECORDING_TICKET, pos, 0, pos);
            pending.add(pos);
        }
    }

    /**
     * Shutdown path: add remaining tickets and immediately force-load synchronously.
     */
    public static void addAndForceLoadRemaining(
            ServerLevel level, List<ChunkPos> toSchedule, Set<ChunkPos> pending, ChunkDataCache cache) {

        for (ChunkPos pos : toSchedule) {
            level.getChunkSource().addRegionTicket(RECORDING_TICKET, pos, 0, pos);
            pending.add(pos);
        }
        toSchedule.clear();

        if (pending.isEmpty()) return;
        LOGGER.info("Force-loading {} chunks synchronously (shutdown)...", pending.size());
        Iterator<ChunkPos> it = pending.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();
            try {
                LevelChunk chunk = level.getChunk(pos.x, pos.z);
                cache.cacheChunkPacket(pos, createPacket(chunk, level));
            } catch (Exception e) {
                LOGGER.error("Failed to force-load chunk {} during shutdown, chunk data will be missing from replay: {}", pos, e.getMessage());
            }
            level.getChunkSource().removeRegionTicket(RECORDING_TICKET, pos, 0, pos);
            it.remove();
        }
    }

    /**
     * Phase 2 poll (called each tick): cache any pending chunks that have finished loading,
     * removing their ticket immediately to spread unload pressure over time.
     *
     * @return true when all pending chunks are cached
     */
    public static boolean checkProgress(
            ServerLevel level, Set<ChunkPos> pending, ChunkDataCache cache) {

        Iterator<ChunkPos> it = pending.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                try {
                    cache.cacheChunkPacket(pos, createPacket(chunk, level));
                    level.getChunkSource().removeRegionTicket(RECORDING_TICKET, pos, 0, pos);
                    it.remove();
                } catch (Exception e) {
                    LOGGER.warn("Failed to cache async-loaded chunk {}, will retry next tick: {}", pos, e.getMessage());
                }
            }
        }
        return pending.isEmpty();
    }

    /**
     * Verification pass: after initial chunk loading completes, scan every position in the
     * recording area and synchronously force-load any chunk that is both unloaded and absent
     * from the cache. Chunks that are currently loaded are fine — writeSnapshot() will read
     * them live — so they are not repaired here.
     *
     * @return number of chunks that required repair
     */
    public static int repairMissingChunks(
            ServerLevel level, ChunkPos center, int radiusInChunks, ChunkDataCache cache) {

        List<ChunkPos> missing = new ArrayList<>();
        for (int dx = -radiusInChunks; dx <= radiusInChunks; dx++) {
            for (int dz = -radiusInChunks; dz <= radiusInChunks; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                if (!cache.hasCachedData(pos) && level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
                    missing.add(pos);
                }
            }
        }
        if (missing.isEmpty()) return 0;

        LOGGER.warn("Verification found {} chunks with missing cache data, repairing before snapshot...", missing.size());
        int repaired = 0;
        for (ChunkPos pos : missing) {
            level.getChunkSource().addRegionTicket(RECORDING_TICKET, pos, 0, pos);
            try {
                LevelChunk chunk = level.getChunk(pos.x, pos.z);
                cache.cacheChunkPacket(pos, createPacket(chunk, level));
                repaired++;
            } catch (Exception e) {
                LOGGER.error("Failed to repair missing chunk {}, it will appear as void in the replay: {}", pos, e.getMessage());
            }
            level.getChunkSource().removeRegionTicket(RECORDING_TICKET, pos, 0, pos);
        }
        LOGGER.info("Chunk repair complete: {}/{} repaired", repaired, missing.size());
        return repaired;
    }

    /**
     * Error/abort cleanup: remove tickets for any positions still in the pending set.
     */
    public static void removeTickets(ServerLevel level, Collection<ChunkPos> positions) {
        for (ChunkPos pos : positions) {
            level.getChunkSource().removeRegionTicket(RECORDING_TICKET, pos, 0, pos);
        }
    }

    static ClientboundLevelChunkWithLightPacket createPacket(LevelChunk chunk, ServerLevel level) {
//#if MC >= 11800
        return new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
//#else
//$$ return new ClientboundLevelChunkPacket(chunk);
//#endif
    }
}
