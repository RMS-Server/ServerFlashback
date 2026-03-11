package com.serverflashback.record;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

public class FlashbackChunkMeta {
    public int duration = 0;
    public boolean forcePlaySnapshot = false;

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("duration", this.duration);
        o.addProperty("forcePlaySnapshot", this.forcePlaySnapshot);
        return o;
    }

    @Nullable
    public static FlashbackChunkMeta fromJson(JsonObject o) {
        FlashbackChunkMeta m = new FlashbackChunkMeta();
        if (!o.has("duration")) return null;
        m.duration = o.get("duration").getAsInt();
        if (o.has("forcePlaySnapshot")) m.forcePlaySnapshot = o.get("forcePlaySnapshot").getAsBoolean();
        return m;
    }
}
