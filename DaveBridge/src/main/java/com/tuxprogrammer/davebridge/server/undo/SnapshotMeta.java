package com.tuxprogrammer.davebridge.server.undo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * What the index knows about one snapshot (PROTOCOL.md 1.10). Plain fields so Gson stores it as-is in
 * {@code index.json}; no Minecraft types.
 */
public final class SnapshotMeta {

    private static final Gson GSON = new Gson();

    /** {@code u<n>}. */
    public String id;
    /** {@code eval} (a confirmed write) or {@code undo} (the record of an undo, which undoes it again: a redo). */
    public String kind;
    /** Who asked for the change (whose confirm it was), or who ran the undo. */
    public String player;
    public String summary;
    /** The start of the script (eval snapshots). */
    public String script;
    /** The eval request's {@code id}, if it had one. */
    public String evalId;
    public long createdAt;
    /** Block positions recorded. */
    public int blocks;
    /** Entities the change spawned. */
    public int entities;
    /** Compressed size on disk, 0 until written. */
    public long bytes;
    public int[] dims;
    /** Bounding box of the positions: minX, minY, minZ, maxX, maxY, maxZ (over all dimensions). */
    public int[] box;
    /** The change touched more positions than {@code undoMaxBlocksPerSnapshot}: the rest were not recorded. */
    public boolean partial;
    /** Things the journal could not see (and so an undo cannot restore). */
    public String[] warnings;
    /** Set once this snapshot was undone. */
    public long undoneAt;
    public String undoneBy;
    /** The snapshot recorded by that undo (undoing it re-applies this change). */
    public String redo;
    /** For {@code kind:"undo"}: the snapshot that was undone. */
    public String undoOf;

    public JsonObject toJson() {
        return GSON.toJsonTree(this)
            .getAsJsonObject();
    }

    /** One line for {@code /dave snapshots}. */
    public String describe(long now) {
        long ageMin = Math.max(0, (now - createdAt) / 60000);
        String age = ageMin < 60 ? ageMin + " min" : ageMin < 2880 ? (ageMin / 60) + " h" : (ageMin / 1440) + " d";
        StringBuilder sb = new StringBuilder();
        sb.append(id)
            .append("  ")
            .append(player)
            .append("  ")
            .append(age)
            .append(" ago  ")
            .append(blocks)
            .append(" blocks");
        if (entities > 0) {
            sb.append(", ")
                .append(entities)
                .append(" entities");
        }
        if (partial) {
            sb.append(" (partial)");
        }
        if (undoneAt != 0) {
            sb.append("  [undone by ")
                .append(undoneBy)
                .append(redo == null ? "" : ", redo " + redo)
                .append("]");
        }
        if ("undo".equals(kind)) {
            sb.append("  [undo of ")
                .append(undoOf)
                .append("]");
        }
        sb.append("  ")
            .append(summary);
        return sb.toString();
    }
}
