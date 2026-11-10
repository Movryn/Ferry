package xyz.movryn.ferry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Json {
    private Json() {
    }

    @SuppressWarnings("deprecation")
    static JsonElement parse(String text) {
        try {
            return new JsonParser().parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static JsonObject object(String text) {
        JsonElement e = parse(text);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    static List<JsonObject> list(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonArray()) return Collections.emptyList();
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonElement x : e.getAsJsonArray()) {
            if (x.isJsonObject()) out.add(x.getAsJsonObject());
        }
        return out;
    }

    static List<JsonObject> array(JsonElement e) {
        if (e == null || !e.isJsonArray()) return Collections.emptyList();
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonElement x : (JsonArray) e) {
            if (x.isJsonObject()) out.add(x.getAsJsonObject());
        }
        return out;
    }
}
