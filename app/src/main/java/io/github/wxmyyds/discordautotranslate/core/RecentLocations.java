package io.github.wxmyyds.discordautotranslate.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Bounded observed channel directory, separate from saved translation rules and remote settings. */
public final class RecentLocations {
    public static final int LIMIT = 128;
    public static final String PREFS = "recent_translation_locations";
    public static final String KEY = "locations";
    public static final String AUTHORITY = "io.github.wxmyyds.discordautotranslate.recent_locations";
    public static final String METHOD = "observe";

    public static final class Entry {
        public final String guildId;
        public final String channelId;

        public Entry(String guildId, String channelId) {
            if (!TranslationScope.isValidId(channelId)
                    || guildId != null && !TranslationScope.isValidId(guildId)) {
                throw new IllegalArgumentException("Invalid observed location");
            }
            this.guildId = guildId;
            this.channelId = channelId;
        }
    }

    private RecentLocations() {}

    public static List<Entry> parse(String json) {
        try {
            org.json.JSONTokener tokener = new org.json.JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONArray) || tokener.nextClean() != 0)
                throw new IllegalArgumentException("Directory must be one JSON array");
            JSONArray array = (JSONArray) value;
            if (array.length() > LIMIT) throw new IllegalArgumentException("Directory capacity exceeded");
            List<Entry> entries = new ArrayList<>();
            java.util.Set<String> channels = new java.util.HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item.length() != 2 || !item.has("guildId") || !item.has("channelId"))
                    throw new IllegalArgumentException("Invalid directory schema");
                Object guild = item.get("guildId");
                Object channel = item.get("channelId");
                if (!(channel instanceof String) || guild != JSONObject.NULL && !(guild instanceof String))
                    throw new IllegalArgumentException("Invalid directory value type");
                Entry entry = new Entry(guild == JSONObject.NULL ? null : (String) guild, (String) channel);
                if (!channels.add(entry.channelId)) throw new IllegalArgumentException("Duplicate channel");
                entries.add(entry);
            }
            return Collections.unmodifiableList(entries);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid recent directory", error);
        }
    }

    public static List<Entry> observe(List<Entry> current, Entry incoming) {
        List<Entry> result = new ArrayList<>();
        result.add(incoming);
        for (Entry entry : current) {
            if (entry.channelId.equals(incoming.channelId)) {
                if (!Objects.equals(entry.guildId, incoming.guildId))
                    throw new IllegalArgumentException("Channel changed server identity");
            } else if (result.size() < LIMIT) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static String toJson(List<Entry> entries) {
        StringBuilder json = new StringBuilder("[");
        for (Entry entry : entries) {
            if (json.length() > 1) json.append(',');
            json.append("{\"guildId\":").append(entry.guildId == null ? "null" : JSONObject.quote(entry.guildId))
                    .append(",\"channelId\":").append(JSONObject.quote(entry.channelId)).append('}');
        }
        return json.append(']').toString();
    }
}
