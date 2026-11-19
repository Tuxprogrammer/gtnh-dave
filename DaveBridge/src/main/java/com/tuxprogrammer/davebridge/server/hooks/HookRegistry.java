package com.tuxprogrammer.davebridge.server.hooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The hooks on this server: ids, caps (total and per owner), per-hook cooldowns, the global firing rate limit, and
 * the outbox of notifications that found no agent connected. Persisted as {@code hooks.json} (with the outbox).
 * Hooks themselves are touched on the server thread only; the outbox is synchronized (agents connect on socket
 * threads). No Minecraft types, so it is unit-tested directly.
 */
public final class HookRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    /** The persisted shape. */
    static final class Stored {

        int v = 1;
        int nextId = 1;
        List<HookSpec> hooks = new ArrayList<>();
        List<String> outbox = new ArrayList<>();
    }

    private final Map<String, HookSpec> hooks = new LinkedHashMap<>();
    private final Deque<Long> recentFires = new ArrayDeque<>();
    private final Deque<String> outbox = new ArrayDeque<>();
    private int nextId = 1;
    private volatile Set<String> activeEvents = new HashSet<>();

    /** Thrown when a cap is reached. */
    public static final class Full extends Exception {

        private static final long serialVersionUID = 1L;

        Full(String message) {
            super(message);
        }
    }

    /** Assigns an id and adds the hook, unless that would exceed {@code maxTotal} or {@code maxPerOwner}. */
    public HookSpec add(HookSpec h, int maxTotal, int maxPerOwner) throws Full {
        if (hooks.size() >= maxTotal) {
            throw new Full("the server already has " + hooks.size() + " hooks (hooks.hookMaxCount); remove some first");
        }
        int mine = forOwner(h.owner).size();
        if (mine >= maxPerOwner) {
            throw new Full(h.owner + " already has " + mine + " hooks (hooks.hookMaxPerOwner); remove some first");
        }
        h.id = "h" + nextId++;
        hooks.put(h.id, h);
        refreshActive();
        return h;
    }

    /** By id (case-insensitive, with or without the {@code h}), or null. */
    public HookSpec get(String id) {
        if (id == null) {
            return null;
        }
        String want = id.trim()
            .toLowerCase(java.util.Locale.ROOT);
        return hooks.get(want.startsWith("h") ? want : "h" + want);
    }

    public HookSpec remove(String id) {
        HookSpec h = get(id);
        if (h != null) {
            hooks.remove(h.id);
            refreshActive();
        }
        return h;
    }

    /** Removes every hook of {@code owner}; returns them. */
    public List<HookSpec> removeOwner(String owner) {
        List<HookSpec> out = forOwner(owner);
        for (HookSpec h : out) {
            hooks.remove(h.id);
        }
        refreshActive();
        return out;
    }

    public List<HookSpec> forOwner(String owner) {
        List<HookSpec> out = new ArrayList<>();
        for (HookSpec h : hooks.values()) {
            if (owner == null || owner.equalsIgnoreCase(h.owner)) {
                out.add(h);
            }
        }
        return out;
    }

    public List<HookSpec> all() {
        return new ArrayList<>(hooks.values());
    }

    /** Enabled hooks of one event type. */
    public List<HookSpec> of(String event) {
        List<HookSpec> out = new ArrayList<>();
        for (HookSpec h : hooks.values()) {
            if (!h.disabled && event.equals(h.event)) {
                out.add(h);
            }
        }
        return out;
    }

    public int size() {
        return hooks.size();
    }

    /** Event types some enabled hook watches. Safe to read from any thread (event handlers check it first). */
    public boolean watches(String event) {
        return activeEvents.contains(event);
    }

    /** Call after a hook was enabled or disabled. */
    public void refreshActive() {
        Set<String> s = new HashSet<>();
        for (HookSpec h : hooks.values()) {
            if (!h.disabled) {
                s.add(h.event);
            }
        }
        activeEvents = s;
    }

    /** Removes and returns hooks past their expiry. */
    public List<HookSpec> expire(long now) {
        List<HookSpec> out = new ArrayList<>();
        for (Iterator<HookSpec> it = hooks.values()
            .iterator(); it.hasNext();) {
            HookSpec h = it.next();
            if (h.expired(now)) {
                it.remove();
                out.add(h);
            }
        }
        if (!out.isEmpty()) {
            refreshActive();
        }
        return out;
    }

    /**
     * Whether {@code h} may fire now: its own cooldown has passed and the server-wide limit of
     * {@code maxPerMinute} firings in the last minute is not reached. A refusal counts as suppressed; an allowed
     * firing is counted against the limit.
     */
    public boolean allowFire(HookSpec h, long now, int maxPerMinute) {
        if (h.lastFiredAt != 0 && now - h.lastFiredAt < h.cooldownSeconds * 1000L) {
            h.suppressed++;
            return false;
        }
        while (!recentFires.isEmpty() && now - recentFires.peekFirst() > 60_000L) {
            recentFires.pollFirst();
        }
        if (recentFires.size() >= maxPerMinute) {
            h.suppressed++;
            return false;
        }
        recentFires.addLast(now);
        return true;
    }

    // ---- outbox

    /** Keeps a notification for the next agent, dropping the oldest beyond {@code max}. */
    public void queue(JsonObject message, int max) {
        synchronized (outbox) {
            if (max <= 0) {
                return;
            }
            outbox.addLast(message.toString());
            while (outbox.size() > max) {
                outbox.pollFirst();
            }
        }
    }

    /** Takes everything queued. */
    public List<String> drainOutbox() {
        synchronized (outbox) {
            List<String> out = new ArrayList<>(outbox);
            outbox.clear();
            return out;
        }
    }

    public int outboxSize() {
        synchronized (outbox) {
            return outbox.size();
        }
    }

    // ---- persistence

    public String toJson() {
        Stored s = new Stored();
        s.nextId = nextId;
        s.hooks = all();
        synchronized (outbox) {
            s.outbox = new ArrayList<>(outbox);
        }
        return GSON.toJson(s);
    }

    /** Replaces the contents with a stored file. Throws IllegalArgumentException on JSON that is not one. */
    public void load(String json) {
        Stored s;
        try {
            s = GSON.fromJson(json, Stored.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("not a hooks file: " + e.getMessage(), e);
        }
        hooks.clear();
        int max = 0;
        if (s != null && s.hooks != null) {
            for (HookSpec h : s.hooks) {
                if (h == null || h.id == null || h.event == null || !HookSpec.EVENTS.contains(h.event)) {
                    continue;
                }
                hooks.put(h.id, h);
                try {
                    max = Math.max(max, Integer.parseInt(h.id.substring(1)));
                } catch (RuntimeException ignored) {
                    // a hand-edited id
                }
            }
        }
        nextId = Math.max(s == null ? 1 : s.nextId, max + 1);
        synchronized (outbox) {
            outbox.clear();
            if (s != null && s.outbox != null) {
                for (String m : s.outbox) {
                    try {
                        if (m != null && new JsonParser().parse(m)
                            .isJsonObject()) {
                            outbox.addLast(m);
                        }
                    } catch (RuntimeException ignored) {
                        // drop a damaged entry
                    }
                }
            }
        }
        refreshActive();
    }
}
