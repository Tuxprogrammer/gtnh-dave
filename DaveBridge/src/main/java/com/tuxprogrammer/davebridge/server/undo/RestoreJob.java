package com.tuxprogrammer.davebridge.server.undo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tuxprogrammer.davebridge.DaveBridge;

/**
 * Puts a snapshot's blocks back, newest change first, a slice per server tick within a time budget. First it removes
 * the entities the change spawned (if they still exist), then restores each position's block, metadata and tile
 * entity. A position someone changed since (its block or metadata is no longer what the change left) is skipped as a
 * conflict unless {@code force}. Item drops while restoring are suppressed (Forge's
 * {@code World.restoringBlockSnapshots}), so restoring over a chest does not spill a copy of its contents. The state
 * each position had just before the undo is recorded as a new snapshot, so the undo itself can be undone.
 * <p>
 * Server thread only.
 */
final class RestoreJob {

    final SnapshotMeta meta;
    final SnapshotData data;
    final boolean force;
    final String by;
    final Consumer<RestoreJob> onDone;
    private int next; // index into data.entries, counting down
    private boolean entitiesDone;
    private boolean done;
    final long startedAt = System.currentTimeMillis();
    int ticks;

    int restored;
    int conflicts;
    int failed;
    int removedEntities;
    int missingEntities;
    final List<String> warnings = new ArrayList<>();
    /** The state before the undo, for the redo snapshot. */
    final List<UndoEntry> redo = new ArrayList<>();
    String redoId;

    RestoreJob(SnapshotMeta meta, SnapshotData data, boolean force, String by, Consumer<RestoreJob> onDone) {
        this.meta = meta;
        this.data = data;
        this.force = force;
        this.by = by;
        this.onDone = onDone;
        this.next = data.entries.size() - 1;
    }

    boolean done() {
        return done;
    }

    private void warn(String w) {
        if (warnings.size() < 20) {
            warnings.add(w);
        } else if (warnings.size() == 20) {
            warnings.add("... more not shown");
        }
    }

    /**
     * Restores until {@code budgetNanos} is used up (at least one position). Never throws.
     *
     * @return true once everything is done
     */
    boolean step(long budgetNanos) {
        if (done) {
            return true;
        }
        ticks++;
        long start = System.nanoTime();
        try {
            if (!entitiesDone) {
                removeEntities();
                entitiesDone = true;
            }
            while (next >= 0) {
                UndoEntry e = data.entries.get(next--);
                try {
                    restore(e);
                } catch (Throwable t) {
                    failed++;
                    warn(e.pos() + ": " + t);
                    DaveBridge.LOG.warn("Undo {}: restoring {} failed", meta.id, e.pos(), t);
                }
                if (System.nanoTime() - start >= budgetNanos) {
                    break;
                }
            }
        } catch (Throwable t) {
            DaveBridge.LOG.warn("Undo {} failed", meta.id, t);
            warn("stopped early: " + t);
            next = -1;
        }
        if (next < 0) {
            done = true;
        }
        return done;
    }

    private void removeEntities() {
        Map<Integer, Map<String, Entity>> byDim = new HashMap<>();
        for (SnapshotData.Spawned s : data.spawned) {
            WorldServer w = DimensionManager.getWorld(s.dim);
            if (w == null) {
                missingEntities++;
                continue;
            }
            Map<String, Entity> loaded = byDim.get(s.dim);
            if (loaded == null) {
                loaded = new HashMap<>();
                for (Object o : w.loadedEntityList) {
                    Entity ent = (Entity) o;
                    loaded.put(
                        ent.getUniqueID()
                            .toString(),
                        ent);
                }
                byDim.put(s.dim, loaded);
            }
            Entity ent = loaded.get(s.uuid);
            if (ent == null || ent.isDead) {
                missingEntities++;
            } else {
                ent.setDead();
                removedEntities++;
            }
        }
        if (missingEntities > 0) {
            warn(
                missingEntities + " spawned entit"
                    + (missingEntities == 1 ? "y was" : "ies were")
                    + " already gone (merged, picked up, despawned) or in an unloaded chunk");
        }
    }

    private void restore(UndoEntry e) {
        // Loads the dimension and the chunk if they are not loaded, as the original change did.
        WorldServer w = MinecraftServer.getServer()
            .worldServerForDimension(e.dim);
        Block cur = w.getBlock(e.x, e.y, e.z);
        int curMeta = w.getBlockMetadata(e.x, e.y, e.z);
        String curName = ChangeJournal.name(cur);
        if (!force && e.afterBlock != null && (!curName.equals(e.afterBlock) || curMeta != e.afterMeta)) {
            conflicts++;
            warn(
                e.pos() + " changed since (now "
                    + curName
                    + (curMeta == 0 ? "" : ":" + curMeta)
                    + "); skipped. force:true restores it anyway");
            return;
        }
        Block target = Block.getBlockFromName(e.block);
        if (target == null) {
            failed++;
            warn(e.pos() + ": block " + e.block + " no longer exists");
            return;
        }
        UndoEntry before = ChangeJournal.current(w, e.x, e.y, e.z);
        boolean old = w.restoringBlockSnapshots;
        w.restoringBlockSnapshots = true; // no item drops from what is replaced
        try {
            if (e.tile != null || before.tile != null) {
                w.removeTileEntity(e.x, e.y, e.z);
            }
            w.setBlock(e.x, e.y, e.z, target, e.meta, 3);
            if (e.tile != null) {
                TileEntity te = TileEntity.createAndLoadEntity((NBTTagCompound) e.tile.copy());
                if (te != null) {
                    w.setTileEntity(e.x, e.y, e.z, te);
                    te.markDirty();
                } else {
                    warn(e.pos() + ": its tile entity (" + e.tile.getString("id") + ") could not be recreated");
                }
            }
            w.markBlockForUpdate(e.x, e.y, e.z);
        } finally {
            w.restoringBlockSnapshots = old;
        }
        redo.add(before.after(e.block, e.meta));
        restored++;
    }

    /** The {@code undo_result} fields. */
    JsonObject toJson() {
        JsonObject r = new JsonObject();
        r.addProperty("snapshot", meta.id);
        r.addProperty("ok", failed == 0);
        r.addProperty("restored", restored);
        r.addProperty("conflicts", conflicts);
        r.addProperty("failed", failed);
        r.addProperty("removedEntities", removedEntities);
        r.addProperty("missingEntities", missingEntities);
        r.addProperty("ticks", ticks);
        r.addProperty("ms", System.currentTimeMillis() - startedAt);
        if (redoId != null) {
            r.addProperty("redo", redoId);
        }
        if (!warnings.isEmpty()) {
            JsonArray w = new JsonArray();
            for (String s : warnings) {
                w.add(new JsonPrimitive(s));
            }
            r.add("warnings", w);
        }
        return r;
    }

    /** One line for chat. */
    String describe() {
        StringBuilder sb = new StringBuilder("Undid ").append(meta.id)
            .append(": ")
            .append(restored)
            .append(" block")
            .append(restored == 1 ? "" : "s")
            .append(" restored");
        if (removedEntities > 0) {
            sb.append(", ")
                .append(removedEntities)
                .append(" spawned entit")
                .append(removedEntities == 1 ? "y" : "ies")
                .append(" removed");
        }
        if (conflicts > 0) {
            sb.append(", ")
                .append(conflicts)
                .append(" skipped (changed since)");
        }
        if (failed > 0) {
            sb.append(", ")
                .append(failed)
                .append(" failed");
        }
        if (redoId != null) {
            sb.append(". Redo: ")
                .append(redoId);
        }
        return sb.append('.')
            .toString();
    }
}
