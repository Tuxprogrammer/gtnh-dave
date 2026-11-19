package com.tuxprogrammer.davebridge.server.hooks;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One event-hook subscription (PROTOCOL.md 1.9): what to watch, the filter, how often it may fire, and its runtime
 * state. Plain fields, so Gson stores it as-is in {@code hooks.json}; no Minecraft types, so parsing, validation and
 * matching are unit-tested directly.
 */
public final class HookSpec {

    public static final String POLL = "poll";
    /** Every event a hook can watch. */
    public static final Set<String> EVENTS = Collections.unmodifiableSet(
        new LinkedHashSet<>(
            Arrays.asList(
                "player_join",
                "player_leave",
                "player_death",
                "player_dimension",
                "entity_death",
                "block_break",
                "block_place",
                "chat",
                "quest_complete",
                POLL)));

    static final int MAX_NAME = 64;
    static final int MAX_NOTE = 500;
    static final int MAX_SCRIPT = 16000;
    static final int MAX_EVERY_TICKS = 72000;

    private static final Gson GSON = new Gson();

    /** Declarative filter; every field set must match (AND). */
    public static final class Filter {

        /** The acting player (for entity_death: the killer), case-insensitive. */
        public String player;
        /** Substring of the entity's registry name, class or custom name (entity_death, player_death's killer). */
        public String entity;
        /** {@code modid:name} or {@code modid:name:meta} (block_break, block_place). */
        public String block;
        public Integer dim;
        /** Inclusive box: x1, y1, z1, x2, y2, z2. */
        public int[] box;
        /** Case-insensitive substring of the chat message or death message. */
        public String contains;
        /** A quest id (UUID) or name (quest_complete). */
        public String quest;
    }

    // ---- definition
    public String id;
    public String event;
    public String name;
    public String owner;
    public String ownerUuid;
    /** Who Dave should tell: {@code owner} (default), {@code @a}, or a player name. Informational. */
    public String notify;
    /** What Dave should do when it fires, in his own words. */
    public String note;
    /** Groovy: the condition of a poll hook, an extra filter of an event hook. */
    public String script;
    public JsonElement params;
    public Filter filter;
    public int everyTicks;
    public boolean once;
    public int cooldownSeconds;
    /** Poll: fire on a false-to-true change only (default), or on every true run. */
    public boolean edge;
    /** Poll: fire when the condition is already true at the first run. */
    public boolean fireIfTrueAtStart;
    public long createdAt;
    /** Epoch millis; 0 = never. */
    public long expiresAt;

    // ---- state
    public int fireCount;
    public long lastFiredAt;
    /** Poll: the condition's value at the last run (null before the first). */
    public Boolean lastValue;
    /** Consecutive script failures. */
    public int errors;
    public String lastError;
    public boolean disabled;
    /** Firings dropped by the cooldown or the global rate limit since the last delivered one. */
    public int suppressed;

    /** Thrown for an invalid hook; the message is safe to show the agent. */
    public static final class Invalid extends Exception {

        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /** Limits that come from the config, passed in so parsing stays testable. */
    public static final class Limits {

        public final int minPollTicks;
        public final int defaultCooldownSeconds;
        public final long defaultTtlMillis;
        /** 0: no limit. */
        public final long maxTtlMillis;

        public Limits(int minPollTicks, int defaultCooldownSeconds, long defaultTtlMillis, long maxTtlMillis) {
            this.minPollTicks = minPollTicks;
            this.defaultCooldownSeconds = defaultCooldownSeconds;
            this.defaultTtlMillis = defaultTtlMillis;
            this.maxTtlMillis = maxTtlMillis;
        }
    }

    /** Parses and validates the {@code hook} object of a {@code hook_add} request. Id and owner UUID are not set. */
    public static HookSpec parse(JsonObject o, Limits limits, long now) throws Invalid {
        HookSpec h = new HookSpec();
        h.event = lower(str(o, "event"));
        if (h.event == null || !EVENTS.contains(h.event)) {
            throw new Invalid("\"event\" must be one of " + String.join(", ", EVENTS));
        }
        h.owner = trim(str(o, "owner"));
        if (h.owner == null || h.owner.isEmpty()) {
            throw new Invalid("\"owner\" is required: the player the hook is for");
        }
        h.name = clip(trim(str(o, "name")), MAX_NAME);
        if (h.name == null || h.name.isEmpty()) {
            h.name = h.event;
        }
        h.note = clip(trim(str(o, "note")), MAX_NOTE);
        h.notify = clip(trim(str(o, "notify")), MAX_NAME);
        if (h.notify == null || h.notify.isEmpty()) {
            h.notify = "owner";
        }
        h.script = str(o, "script");
        if (h.script != null && h.script.trim()
            .isEmpty()) {
            h.script = null;
        }
        if (h.script != null && h.script.length() > MAX_SCRIPT) {
            throw new Invalid("script too long (" + h.script.length() + " chars, at most " + MAX_SCRIPT + ")");
        }
        if (POLL.equals(h.event) && h.script == null) {
            throw new Invalid("a poll hook needs \"script\": a Groovy condition, run every everyTicks ticks");
        }
        h.params = o.has("params") && !o.get("params")
            .isJsonNull() ? o.get("params") : null;
        h.filter = parseFilter(o.get("filter"));
        h.everyTicks = (int) num(o, "everyTicks", 100);
        if (h.everyTicks < limits.minPollTicks || h.everyTicks > MAX_EVERY_TICKS) {
            if (POLL.equals(h.event)) {
                throw new Invalid("everyTicks must be " + limits.minPollTicks + ".." + MAX_EVERY_TICKS);
            }
            h.everyTicks = 100;
        }
        h.once = bool(o, "once", true);
        h.cooldownSeconds = (int) num(o, "cooldownSeconds", limits.defaultCooldownSeconds);
        if (h.cooldownSeconds < 0 || h.cooldownSeconds > 86400 * 30) {
            throw new Invalid("cooldownSeconds must be 0..2592000");
        }
        h.edge = bool(o, "edge", true);
        h.fireIfTrueAtStart = bool(o, "fireIfTrueAtStart", false);
        h.createdAt = now;
        long ttl;
        if (o.has("expiresInSeconds") && !o.get("expiresInSeconds")
            .isJsonNull()) {
            long s = num(o, "expiresInSeconds", 0);
            if (s < 0) {
                throw new Invalid("expiresInSeconds must be >= 0 (0: never, where the server allows it)");
            }
            ttl = s == 0 ? limits.maxTtlMillis : s * 1000L;
            if (s == 0 && limits.maxTtlMillis == 0) {
                ttl = 0;
            }
        } else {
            ttl = limits.defaultTtlMillis;
        }
        if (limits.maxTtlMillis > 0 && (ttl == 0 || ttl > limits.maxTtlMillis)) {
            ttl = limits.maxTtlMillis;
        }
        h.expiresAt = ttl == 0 ? 0 : now + ttl;
        return h;
    }

    static Filter parseFilter(JsonElement el) throws Invalid {
        Filter f = new Filter();
        if (el == null || el.isJsonNull()) {
            return f;
        }
        if (!el.isJsonObject()) {
            throw new Invalid("\"filter\" must be an object");
        }
        JsonObject o = el.getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) { // Gson 2.2: no keySet()
            String key = e.getKey();
            if (!Arrays.asList("player", "entity", "block", "dim", "box", "contains", "quest")
                .contains(key)) {
                throw new Invalid(
                    "unknown filter key \"" + key + "\" (player, entity, block, dim, box, contains, quest)");
            }
        }
        f.player = trim(str(o, "player"));
        f.entity = trim(str(o, "entity"));
        f.block = trim(str(o, "block"));
        f.contains = trim(str(o, "contains"));
        f.quest = trim(str(o, "quest"));
        if (o.has("dim") && !o.get("dim")
            .isJsonNull()) {
            f.dim = (int) num(o, "dim", 0);
        }
        if (o.has("box") && !o.get("box")
            .isJsonNull()) {
            JsonElement b = o.get("box");
            if (!b.isJsonArray() || b.getAsJsonArray()
                .size() != 6) {
                throw new Invalid("filter.box must be [x1,y1,z1,x2,y2,z2]");
            }
            JsonArray a = b.getAsJsonArray();
            f.box = new int[6];
            try {
                for (int i = 0; i < 6; i++) {
                    f.box[i] = (int) Math.floor(
                        a.get(i)
                            .getAsDouble());
                }
            } catch (RuntimeException e) {
                throw new Invalid("filter.box must be six numbers");
            }
        }
        return f;
    }

    // ---- matching

    /**
     * True when the declarative filter matches an event's facts (see {@code HookEvents} for the keys: player,
     * killer, entity, entityClass, entityName, block, meta, dim, x, y, z, message, questIds, questNames).
     */
    public boolean matches(Map<String, Object> facts) {
        Filter f = filter;
        if (f == null) {
            return true;
        }
        if (f.player != null) {
            String who = "entity_death".equals(event) ? s(facts.get("killer")) : s(facts.get("player"));
            if (who == null || !who.equalsIgnoreCase(f.player)) {
                return false;
            }
        }
        if (f.entity != null) {
            String want = f.entity.toLowerCase(Locale.ROOT);
            boolean hit = false;
            for (String k : new String[] { "entity", "entityClass", "entityName", "killerEntity" }) {
                String v = s(facts.get(k));
                if (v != null && v.toLowerCase(Locale.ROOT)
                    .contains(want)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        if (f.block != null && !blockMatches(f.block, s(facts.get("block")), facts.get("meta"))) {
            return false;
        }
        if (f.dim != null && !f.dim.equals(intOf(facts.get("dim")))) {
            return false;
        }
        if (f.box != null) {
            Integer x = intOf(facts.get("x")), y = intOf(facts.get("y")), z = intOf(facts.get("z"));
            if (x == null || y == null
                || z == null
                || !in(x, f.box[0], f.box[3])
                || !in(y, f.box[1], f.box[4])
                || !in(z, f.box[2], f.box[5])) {
                return false;
            }
        }
        if (f.contains != null) {
            String want = f.contains.toLowerCase(Locale.ROOT);
            String m = s(facts.get("message"));
            if (m == null || !m.toLowerCase(Locale.ROOT)
                .contains(want)) {
                return false;
            }
        }
        if (f.quest != null && !questMatches(f.quest, facts.get("questIds"), facts.get("questNames"))) {
            return false;
        }
        return true;
    }

    static boolean blockMatches(String want, String block, Object meta) {
        if (block == null) {
            return false;
        }
        String w = want.toLowerCase(Locale.ROOT);
        int colon = w.lastIndexOf(':');
        if (colon > 0 && w.indexOf(':') != colon
            && w.substring(colon + 1)
                .matches("\\d+")) {
            Integer m = intOf(meta);
            return block.equalsIgnoreCase(w.substring(0, colon)) && m != null
                && m == Integer.parseInt(w.substring(colon + 1));
        }
        if (w.indexOf(':') < 0) {
            w = "minecraft:" + w;
        }
        return block.equalsIgnoreCase(w);
    }

    private static boolean questMatches(String want, Object ids, Object names) {
        String w = want.toLowerCase(Locale.ROOT);
        if (ids instanceof Collection) {
            for (Object id : (Collection<?>) ids) {
                if (id != null && id.toString()
                    .equalsIgnoreCase(want)) {
                    return true;
                }
            }
        }
        if (names instanceof Collection) {
            for (Object n : (Collection<?>) names) {
                String plain = n == null ? null
                    : n.toString()
                        .replaceAll("§.", "")
                        .trim()
                        .toLowerCase(Locale.ROOT);
                if (plain != null && (plain.equals(w) || plain.contains(w))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean in(int v, int a, int b) {
        return v >= Math.min(a, b) && v <= Math.max(a, b);
    }

    /** True when the hook should be removed for its age. */
    public boolean expired(long now) {
        return expiresAt != 0 && now > expiresAt;
    }

    public JsonObject toJson() {
        return GSON.toJsonTree(this)
            .getAsJsonObject();
    }

    /** One line for {@code /dave hooks}. */
    public String describe(long now) {
        StringBuilder sb = new StringBuilder();
        sb.append(id)
            .append("  ")
            .append(event)
            .append("  ")
            .append(owner)
            .append("  \"")
            .append(name)
            .append('"');
        if (POLL.equals(event)) {
            sb.append("  every ")
                .append(everyTicks)
                .append(" ticks");
        }
        sb.append(once ? "  once" : "  repeating")
            .append("  fired ")
            .append(fireCount);
        if (disabled) {
            sb.append("  DISABLED: ")
                .append(lastError);
        } else if (errors > 0) {
            sb.append("  ")
                .append(errors)
                .append(" error(s): ")
                .append(lastError);
        }
        if (expiresAt != 0) {
            long h = Math.max(0, (expiresAt - now) / 3_600_000L);
            sb.append("  expires in ")
                .append(h < 48 ? h + " h" : (h / 24) + " d");
        }
        return sb.toString();
    }

    // ---- small helpers

    static String str(JsonObject o, String key) throws Invalid {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonPrimitive()) {
            throw new Invalid("\"" + key + "\" must be a string");
        }
        return el.getAsString();
    }

    static long num(JsonObject o, String key, long def) throws Invalid {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return def;
        }
        try {
            return (long) el.getAsDouble();
        } catch (RuntimeException e) {
            throw new Invalid("\"" + key + "\" must be a number");
        }
    }

    static boolean bool(JsonObject o, String key, boolean def) throws Invalid {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return def;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive()
            .isBoolean()) {
            throw new Invalid("\"" + key + "\" must be true or false");
        }
        return el.getAsBoolean();
    }

    private static String s(Object o) {
        return o == null ? null : o.toString();
    }

    static Integer intOf(Object o) {
        if (o instanceof Number) {
            return (int) Math.floor(((Number) o).doubleValue());
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? null
            : s.trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
