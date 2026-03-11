package com.serverflashback.chunk;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ChunkForceLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverflashback");

    public static Map<ChunkPos, ClientboundLevelChunkWithLightPacket> forceLoadAndCreatePackets(
            ServerLevel level, ChunkPos center, int radiusInChunks) {

        Map<ChunkPos, ClientboundLevelChunkWithLightPacket> result = new HashMap<>();
        int total = (2 * radiusInChunks + 1) * (2 * radiusInChunks + 1);
        int loaded = 0;

        LOGGER.info("Force-loading {} chunks (center: {}, radius: {})", total, center, radiusInChunks);

        for (int dx = -radiusInChunks; dx <= radiusInChunks; dx++) {
            for (int dz = -radiusInChunks; dz <= radiusInChunks; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                try {
                    LevelChunk chunk = level.getChunk(pos.x, pos.z);
//#if MC >= 11800
                    ClientboundLevelChunkWithLightPacket packet =
                            new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
//#else
//$$ ClientboundLevelChunkPacket packet = new ClientboundLevelChunkPacket(chunk);
//#endif
                    result.put(pos, packet);
                    loaded++;
                    if (loaded % 200 == 0) {
                        LOGGER.info("Force-loaded {}/{} chunks...", loaded, total);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load chunk {}: {}", pos, e.getMessage());
                }
            }
        }

        LOGGER.info("Force-loading complete: {}/{} chunks", loaded, total);
        return result;
    }
}
