package com.tuxprogrammer.davebridge.server.undo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * The list of snapshots, oldest first, with id allocation and pruning by count, total size and age. Thread-safe (all
 * methods synchronized); no Minecraft types, so it is unit-tested directly. Persisted as {@code index.json}.
 */
public final class SnapshotIndex {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    /** The persisted shape. */
    static final class Stored {

        int v = 1;
        int nextId = 1;
        List<SnapshotMeta> snapshots = new ArrayList<>();
    }

    private Stored data = new Stored();

    public synchronized String newId() {
        return "u" + data.nextId++;
    }

    public synchronized void add(SnapshotMeta meta) {
        data.snapshots.add(meta);
    }

    /** By id (case-insensitive, with or without the {@code u}), or null. */
    public synchronized SnapshotMeta get(String id) {
        if (id == null) {
            return null;
        }
        String want = id.trim()
            .toLowerCase(java.util.Locale.ROOT);
        if (!want.startsWith("u")) {
            want = "u" + want;
        }
        for (SnapshotMeta m : data.snapshots) {
            if (m.id.equals(want)) {
                return m;
            }
        }
        return null;
    }

    /**
     * The newest snapshot not yet undone, of {@code player} (case-insensitive) or of anyone when null. Undo records
     * count too, so "undo last" twice is undo then redo.
     */
    public synchronized SnapshotMeta last(String player) {
        for (int i = data.snapshots.size() - 1; i >= 0; i--) {
            SnapshotMeta m = data.snapshots.get(i);
            if (m.undoneAt == 0 && (player == null || player.equalsIgnoreCase(m.player))) {
                return m;
            }
        }
        return null;
    }

    /** Newest first, at most {@code limit}, optionally only {@code player}'s. */
    public synchronized List<SnapshotMeta> list(String player, int limit) {
        List<SnapshotMeta> out = new ArrayList<>();
        for (int i = data.snapshots.size() - 1; i >= 0 && out.size() < limit; i--) {
            SnapshotMeta m = data.snapshots.get(i);
            if (player == null || player.equalsIgnoreCase(m.player)) {
                out.add(m);
            }
        }
        return out;
    }

    public synchronized int size() {
        return data.snapshots.size();
    }

    public synchronized long totalBytes() {
        long n = 0;
        for (SnapshotMeta m : data.snapshots) {
            n += m.bytes;
        }
        return n;
    }

    /**
     * Removes the oldest snapshots until at most {@code maxCount} remain and they use at most {@code maxBytes}, and
     * every snapshot older than {@code maxAgeMillis} (0: no age limit). The newest snapshot is never removed for size
     * alone, so one oversized change can still be undone.
     *
     * @return what was removed (delete their files)
     */
    public synchronized List<SnapshotMeta> prune(int maxCount, long maxBytes, long maxAgeMillis, long now) {
        List<SnapshotMeta> removed = new ArrayList<>();
        for (Iterator<SnapshotMeta> it = data.snapshots.iterator(); it.hasNext();) {
            SnapshotMeta m = it.next();
            if (maxAgeMillis > 0 && now - m.createdAt > maxAgeMillis) {
                it.remove();
                removed.add(m);
            }
        }
        while (data.snapshots.size() > Math.max(1, maxCount)
            || (data.snapshots.size() > 1 && totalBytes() > maxBytes)) {
            removed.add(data.snapshots.remove(0));
        }
        return removed;
    }

    public synchronized String toJson() {
        return GSON.toJson(data);
    }

    /** Replaces the contents with a stored index. Throws on JSON that is not an index. */
    public synchronized void load(String json) {
        Stored s;
        try {
            s = GSON.fromJson(json, Stored.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("not a snapshot index: " + e.getMessage(), e);
        }
        if (s == null) {
            s = new Stored();
        }
        if (s.snapshots == null) {
            s.snapshots = new ArrayList<>();
        }
        s.snapshots.removeIf(m -> m == null || m.id == null);
        int max = 0;
        for (SnapshotMeta m : s.snapshots) {
            try {
                max = Math.max(max, Integer.parseInt(m.id.substring(1)));
            } catch (RuntimeException ignored) {
                // a hand-edited id; nextId still moves past every numeric one
            }
        }
        s.nextId = Math.max(s.nextId, max + 1);
        data = s;
    }
}
