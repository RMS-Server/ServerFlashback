package cn.net.rms.serverflashback.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
//#if MC >= 11903
import org.joml.Vector3f;
//#else
//$$ import com.mojang.math.Vector3f;
//#endif
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class FlashbackMeta {

    public UUID replayIdentifier = UUID.randomUUID();
    public String name = "Unnamed";
    public String versionString = null;
    public String worldName = null;
    public int dataVersion = 0;
    public int protocolVersion = 0;

    public TreeMap<Integer, ReplayMarker> replayMarkers = new TreeMap<>();

    public int totalTicks = -1;
    public LinkedHashMap<String, FlashbackChunkMeta> chunks = new LinkedHashMap<>();

    public LinkedHashMap<String, LinkedHashSet<String>> namespacesForRegistries = null;

    public JsonObject toJson() {
        JsonObject meta = new JsonObject();
        meta.addProperty("uuid", this.replayIdentifier.toString());
        meta.addProperty("name", this.name);

        if (this.versionString != null) meta.addProperty("version_string", this.versionString);
        if (this.worldName != null) meta.addProperty("world_name", this.worldName);
        meta.addProperty("data_version", this.dataVersion);
        meta.addProperty("protocol_version", this.protocolVersion);
        if (this.totalTicks > 0) meta.addProperty("total_ticks", this.totalTicks);

        if (!this.replayMarkers.isEmpty()) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(Vector3f.class, new Vector3fTypeAdapter())
                    .create();
            JsonObject jsonMarkers = new JsonObject();
            for (Map.Entry<Integer, ReplayMarker> entry : this.replayMarkers.entrySet()) {
                jsonMarkers.add("" + entry.getKey(), gson.toJsonTree(entry.getValue()));
            }
            meta.add("markers", jsonMarkers);
        }

        if (this.namespacesForRegistries != null) {
            JsonObject registriesObj = new JsonObject();
            for (Map.Entry<String, LinkedHashSet<String>> entry : this.namespacesForRegistries.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String ns : entry.getValue()) arr.add(ns);
                registriesObj.add(entry.getKey(), arr);
            }
            meta.add("customNamespacesForRegistries", registriesObj);
        }

        JsonObject chunksJson = new JsonObject();
        for (Map.Entry<String, FlashbackChunkMeta> entry : this.chunks.entrySet()) {
            chunksJson.add(entry.getKey(), entry.getValue().toJson());
        }
        meta.add("chunks", chunksJson);

        return meta;
    }

    @Nullable
    public static FlashbackMeta fromJson(JsonObject meta) {
        FlashbackMeta m = new FlashbackMeta();
        if (!meta.has("uuid")) return null;
        m.replayIdentifier = UUID.fromString(meta.get("uuid").getAsString());
        if (!meta.has("name")) return null;
        m.name = meta.get("name").getAsString();
        if (meta.has("version_string")) m.versionString = meta.get("version_string").getAsString();
        if (meta.has("world_name")) m.worldName = meta.get("world_name").getAsString();
        if (meta.has("data_version")) m.dataVersion = meta.get("data_version").getAsInt();
        if (meta.has("protocol_version")) m.protocolVersion = meta.get("protocol_version").getAsInt();
        if (meta.has("total_ticks")) m.totalTicks = meta.get("total_ticks").getAsInt();

        if (meta.has("markers")) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(Vector3f.class, new Vector3fTypeAdapter())
                    .create();
            for (Map.Entry<String, JsonElement> entry : meta.getAsJsonObject("markers").entrySet()) {
                try {
                    m.replayMarkers.put(Integer.parseInt(entry.getKey()),
                            gson.fromJson(entry.getValue(), ReplayMarker.class));
                } catch (Exception ignored) {}
            }
        }

        if (meta.has("customNamespacesForRegistries")) {
            m.namespacesForRegistries = new LinkedHashMap<>();
            JsonObject obj = meta.getAsJsonObject("customNamespacesForRegistries");
            for (Map.Entry<String, com.google.gson.JsonElement> kv : obj.entrySet()) {
                String key = kv.getKey();
                LinkedHashSet<String> ns = new LinkedHashSet<>();
                for (JsonElement el : obj.get(key).getAsJsonArray()) ns.add(el.getAsString());
                m.namespacesForRegistries.put(key, ns);
            }
        }

        if (!meta.has("chunks")) return null;
        for (Map.Entry<String, JsonElement> entry : meta.getAsJsonObject("chunks").entrySet()) {
            FlashbackChunkMeta cm = FlashbackChunkMeta.fromJson(entry.getValue().getAsJsonObject());
            if (cm == null) return null;
            m.chunks.put(entry.getKey(), cm);
        }
        return m;
    }
}
