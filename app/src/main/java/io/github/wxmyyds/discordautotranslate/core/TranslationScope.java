package io.github.wxmyyds.discordautotranslate.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable translation policy: channel rule, then server rule, then the default. */
public final class TranslationScope {
    public static final int MAX_RULES = 256;
    public static final int MAX_LABEL_LENGTH = 80;

    public static final TranslationScope ALL = new TranslationScope(
            true, Collections.emptyList(), Collections.emptyList());

    public final boolean defaultTranslate;
    public final List<Rule> servers;
    public final List<Rule> channels;

    public TranslationScope(boolean defaultTranslate, List<Rule> servers, List<Rule> channels) {
        this.defaultTranslate = defaultTranslate;
        this.servers = copyRules(servers, "servers");
        this.channels = copyRules(channels, "channels");
    }

    /** A label is display-only; IDs are the sole matching keys. */
    public static final class Rule {
        public final String id;
        public final String label;
        public final boolean translate;

        public Rule(String id, String label, boolean translate) {
            if (!isValidId(id)) {
                throw new IllegalArgumentException("Invalid rule ID");
            }
            if (label == null) {
                throw new IllegalArgumentException("Rule label must not be null");
            }
            String trimmedLabel = label.trim();
            if (trimmedLabel.length() > MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException("Rule label exceeds " + MAX_LABEL_LENGTH + " characters");
            }
            this.id = id;
            this.label = trimmedLabel;
            this.translate = translate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Rule)) return false;
            Rule that = (Rule) other;
            return translate == that.translate && id.equals(that.id) && label.equals(that.label);
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + label.hashCode();
            return 31 * result + Boolean.hashCode(translate);
        }
    }

    /** Accepts canonical positive ASCII decimal unsigned 64-bit IDs. */
    public static boolean isValidId(String id) {
        if (id == null || id.isEmpty() || id.length() > 20 || id.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char digit = id.charAt(i);
            if (digit < '0' || digit > '9') return false;
        }
        return new BigInteger(id).bitLength() <= 64;
    }

    public static TranslationScope parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Translation scope JSON must not be empty or null");
        }
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IllegalArgumentException("Translation scope must be one JSON object");
            }
            JSONObject root = (JSONObject) value;
            requireKeys(root, "defaultTranslate", "servers", "channels");
            Object defaultValue = root.get("defaultTranslate");
            if (!(defaultValue instanceof Boolean)) {
                throw new IllegalArgumentException("defaultTranslate must be a boolean");
            }
            return new TranslationScope((Boolean) defaultValue,
                    parseRules(root.get("servers"), "servers"),
                    parseRules(root.get("channels"), "channels"));
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid translation scope JSON", error);
        }
    }

    private static List<Rule> parseRules(Object value, String name) throws JSONException {
        if (!(value instanceof JSONArray)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        JSONArray array = (JSONArray) value;
        if (array.length() > MAX_RULES) {
            throw new IllegalArgumentException(name + " exceeds 256 rules");
        }
        List<Rule> rules = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) {
                throw new IllegalArgumentException(name + " rule must be an object");
            }
            JSONObject object = (JSONObject) item;
            requireKeys(object, "id", "label", "translate");
            Object id = object.get("id");
            Object label = object.get("label");
            Object translate = object.get("translate");
            if (!(id instanceof String) || !(label instanceof String)
                    || !(translate instanceof Boolean)) {
                throw new IllegalArgumentException("Rule requires string id/label and boolean translate");
            }
            rules.add(new Rule((String) id, (String) label, (Boolean) translate));
        }
        return rules;
    }

    private static void requireKeys(JSONObject object, String first, String second, String third) {
        Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            if (!first.equals(key) && !second.equals(key) && !third.equals(key)) {
                throw new IllegalArgumentException("Unknown translation scope key: " + key);
            }
            count++;
        }
        if (count != 3 || !object.has(first) || !object.has(second) || !object.has(third)) {
            throw new IllegalArgumentException("Translation scope object requires exactly "
                    + first + ", " + second + ", " + third);
        }
    }

    private static List<Rule> copyRules(List<Rule> rules, String name) {
        if (rules == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException(name + " exceeds 256 rules");
        }
        List<Rule> copy = new ArrayList<>(rules.size());
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            if (rule == null) {
                throw new IllegalArgumentException(name + " must not contain null rules");
            }
            if (!ids.add(rule.id)) {
                throw new IllegalArgumentException("Duplicate " + name + " rule ID: " + rule.id);
            }
            copy.add(rule);
        }
        return Collections.unmodifiableList(copy);
    }

    public boolean allows(String guildId, String channelId) {
        if (!isValidId(channelId) || (guildId != null && !isValidId(guildId))) {
            return false;
        }
        for (Rule rule : channels) {
            if (rule.id.equals(channelId)) return rule.translate;
        }
        if (guildId != null) {
            for (Rule rule : servers) {
                if (rule.id.equals(guildId)) return rule.translate;
            }
        }
        return defaultTranslate;
    }

    /** Compare matching policy only: labels and rule order are display/serialization details. */
    public boolean sameMatchingPolicy(TranslationScope other) {
        return other != null && defaultTranslate == other.defaultTranslate
                && sameRuleActions(servers, other.servers)
                && sameRuleActions(channels, other.channels);
    }

    private static boolean sameRuleActions(List<Rule> rules, List<Rule> other) {
        if (rules.size() != other.size()) return false;
        Map<String, Boolean> actions = new HashMap<>();
        for (Rule rule : other) actions.put(rule.id, rule.translate);
        for (Rule rule : rules) {
            if (!Boolean.valueOf(rule.translate).equals(actions.get(rule.id))) return false;
        }
        return true;
    }

    /** Fixed key order and list order make the compact representation stable. */
    public String toJson() {
        StringBuilder json = new StringBuilder("{\"defaultTranslate\":");
        json.append(defaultTranslate).append(",\"servers\":");
        appendRules(json, servers);
        json.append(",\"channels\":");
        appendRules(json, channels);
        return json.append('}').toString();
    }

    private static void appendRules(StringBuilder json, List<Rule> rules) {
        json.append('[');
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) json.append(',');
            Rule rule = rules.get(i);
            json.append("{\"id\":").append(JSONObject.quote(rule.id))
                    .append(",\"label\":").append(JSONObject.quote(rule.label))
                    .append(",\"translate\":").append(rule.translate).append('}');
        }
        json.append(']');
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TranslationScope)) return false;
        TranslationScope that = (TranslationScope) other;
        return defaultTranslate == that.defaultTranslate
                && servers.equals(that.servers) && channels.equals(that.channels);
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(defaultTranslate);
        result = 31 * result + servers.hashCode();
        return 31 * result + channels.hashCode();
    }
}
