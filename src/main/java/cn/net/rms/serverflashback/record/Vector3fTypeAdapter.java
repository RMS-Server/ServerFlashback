package cn.net.rms.serverflashback.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonDeserializer;

import java.lang.reflect.Type;
//#if MC >= 11903
import org.joml.Vector3f;
//#else
//$$ import com.mojang.math.Vector3f;
//#endif

public class Vector3fTypeAdapter implements JsonSerializer<Vector3f>, JsonDeserializer<Vector3f> {

    @Override
    public JsonElement serialize(Vector3f src, Type typeOfSrc, JsonSerializationContext context) {
        JsonArray arr = new JsonArray();
        arr.add(src.x());
        arr.add(src.y());
        arr.add(src.z());
        return arr;
    }

    @Override
    public Vector3f deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonArray arr = json.getAsJsonArray();
        if (arr.size() != 3) {
            throw new JsonParseException("Vector3f expected array of 3, got " + arr.size());
        }
        float x = arr.get(0).getAsFloat();
        float y = arr.get(1).getAsFloat();
        float z = arr.get(2).getAsFloat();
        return new Vector3f(x, y, z);
    }
}
