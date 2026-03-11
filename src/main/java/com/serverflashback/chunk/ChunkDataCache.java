package com.serverflashback.chunk;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkDataCache {

    private final Map<Long, ClientboundLevelChunkWithLightPacket> cache = new ConcurrentHashMap<>();

    public void cacheChunkPacket(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        cache.put(pos.toLong(), packet);
    }

    public ClientboundLevelChunkWithLightPacket getCachedPacket(ChunkPos pos) {
        return cache.get(pos.toLong());
    }

    public void onChunkLoad(ChunkPos pos) {
        cache.remove(pos.toLong());
    }

    public boolean hasCachedData(ChunkPos pos) {
        return cache.containsKey(pos.toLong());
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }
}
