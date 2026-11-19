package com.tuxprogrammer.davebridge.server.undo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.tuxprogrammer.davebridge.DaveBridge;

/**
 * Records what one confirmed world-changing script changes, so it can be undone (PROTOCOL.md 1.10).
 * <p>
 * <b>How.</b> While the script runs, every loaded world has Forge's block capture on
 * ({@code World.captureBlockSnapshots}, the mechanism Forge uses for block placement events), with its
 * {@code capturedBlockSnapshots} list replaced by a recording list. {@code World.setBlock} then hands each change's
 * before-state (block, metadata, tile entity NBT) to the journal before applying it. Capture also defers the change's
 * neighbor and client updates, exactly as for a player placing a block; {@link #end} replays them (Forge's own replay,
 * {@code ForgeHooks.onPlaceItemIntoWorld}), still capturing, so blocks that pop off as a result (a torch on a
 * removed wall) are recorded too, for up to {@value #REPLAY_ROUNDS} rounds. Explicit saves ({@link #saveBlock},
 * {@link #saveRegion}) record positions the script changes in other ways (metadata, inventories). Entities that join
 * a world during the script (item drops, spawned mobs; not entities of chunks it loads) are recorded so an undo can
 * remove them.
 * <p>
 * When the journal closes, each recorded position's current state is read; positions that end as they started are
 * dropped, the rest keep their after-state so a later undo can tell when someone changed them since.
 * <p>
 * Server thread only. At most one journal is open at a time.
 */
public final class ChangeJournal {

    static final int REPLAY_ROUNDS = 8;

    private static ChangeJournal active;

    /** The journal of the script running now, or null. Server thread. */
    public static ChangeJournal active() {
        return active;
    }

    /** What the journal recorded. */
    public static final class Result {

        public final List<UndoEntry> entries;
        public final List<SnapshotData.Spawned> spawned;
        public final List<String> warnings;
        public final boolean partial;

        Result(List<UndoEntry> entries, List<SnapshotData.Spawned> spawned, List<String> warnings, boolean partial) {
            this.entries = entries;
            this.spawned = spawned;
            this.warnings = warnings;
            this.partial = partial;
        }

        public boolean isEmpty() {
            return entries.isEmpty() && spawned.isEmpty();
        }
    }

    /** The capture installed on one world, and what was there before. */
    private final class Capture {

        final WorldServer world;
        final boolean oldCapture;
        final ArrayList<BlockSnapshot> oldList;
        final Recording list;

        Capture(WorldServer world) {
            this.world = world;
            this.oldCapture = world.captureBlockSnapshots;
            this.oldList = world.capturedBlockSnapshots;
            this.list = new Recording(world);
        }
    }

    /** Forge's capture list, recording each snapshot as it is added and keeping it for the update replay. */
    private final class Recording extends ArrayList<BlockSnapshot> {

        private static final long serialVersionUID = 1L;
        final transient World world;
        final transient List<BlockSnapshot> toNotify = new ArrayList<>();

        Recording(World world) {
            this.world = world;
        }

        @Override
        public boolean add(BlockSnapshot s) {
            super.add(s);
            try {
                record(world, s);
                toNotify.add(s);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Undo journal: could not record a block change", t);
            }
            return true;
        }

        /** World.setBlock removes the snapshot again when nothing changed. */
        @Override
        public boolean remove(Object o) {
            int i = toNotify.lastIndexOf(o);
            if (i >= 0) {
                toNotify.remove(i);
            }
            return super.remove(o);
        }
    }

    private final Map<String, UndoEntry> entries = new LinkedHashMap<>();
    private final List<SnapshotData.Spawned> spawned = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<World, Capture> captures = new IdentityHashMap<>();
    private final int maxEntries;
    private final int maxRegionBlocks;
    private int regionBlocks;
    private int explicitSaves;
    private boolean partial;

    private ChangeJournal(int maxEntries, int maxRegionBlocks) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxRegionBlocks = maxRegionBlocks;
    }

    /**
     * Opens a journal on every loaded world. Returns null when one is already open (a nested call). Always pair with
     * {@link #end} in a finally block.
     */
    public static ChangeJournal begin(int maxEntries, int maxRegionBlocks) {
        if (active != null) {
            return null;
        }
        ChangeJournal j = new ChangeJournal(maxEntries, maxRegionBlocks);
        for (WorldServer w : DimensionManager.getWorlds()) {
            if (w == null || w.isRemote) {
                continue;
            }
            if (w.captureBlockSnapshots) {
                j.warn(
                    "dimension " + w.provider.dimensionId
                        + " was already capturing blocks; its changes are not "
                        + "journaled");
                continue;
            }
            Capture c = j.new Capture(w);
            w.capturedBlockSnapshots = c.list;
            w.captureBlockSnapshots = true;
            j.captures.put(w, c);
        }
        active = j;
        return j;
    }

    private void warn(String w) {
        if (warnings.size() < 20 && !warnings.contains(w)) {
            warnings.add(w);
        }
    }

    /** The registry name of a block, e.g. {@code minecraft:stone}. */
    public static String name(Block block) {
        Object n = block == null ? null : Block.blockRegistry.getNameForObject(block);
        return n == null ? "minecraft:air" : n.toString();
    }

    private boolean room(String key) {
        if (entries.containsKey(key)) {
            return false;
        }
        if (entries.size() >= maxEntries) {
            if (!partial) {
                warn("the change touched more than " + maxEntries + " blocks; only the first were recorded");
            }
            partial = true;
            return false;
        }
        return true;
    }

    private void record(World world, BlockSnapshot s) {
        String key = UndoEntry.key(world.provider.dimensionId, s.x, s.y, s.z);
        if (!room(key)) {
            return;
        }
        NBTTagCompound tag = new NBTTagCompound();
        s.writeToNBT(tag);
        NBTTagCompound tile = tag.getBoolean("hasTE") ? tag.getCompoundTag("tileEntity") : null;
        entries.put(key, new UndoEntry(world.provider.dimensionId, s.x, s.y, s.z, name(s.replacedBlock), s.meta, tile));
    }

    /**
     * Records a position's current state (if not recorded yet), before the script changes it some way the capture
     * cannot see: metadata, an inventory, a machine's settings. Loads the chunk if needed.
     *
     * @return true if it was recorded now
     */
    public boolean saveBlock(World world, int x, int y, int z) {
        if (world == null || y < 0 || y > 255) {
            return false;
        }
        explicitSaves++;
        String key = UndoEntry.key(world.provider.dimensionId, x, y, z);
        if (!room(key)) {
            return false;
        }
        entries.put(key, current(world, x, y, z));
        return true;
    }

    /** How many explicit saves the script made (undo.save, undo.region, dave.te), recorded or already recorded. */
    public int explicitSaves() {
        return explicitSaves;
    }

    /**
     * Records every position of a cuboid (corners inclusive, any order) that is not recorded yet.
     *
     * @return how many positions were recorded now
     * @throws IllegalArgumentException when the regions of this journal would exceed {@code undoMaxRegionBlocks}
     */
    public int saveRegion(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
        int ay = Math.max(0, Math.min(y1, y2)), by = Math.min(255, Math.max(y1, y2));
        int az = Math.min(z1, z2), bz = Math.max(z1, z2);
        long volume = (long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1);
        if (by < ay) {
            return 0;
        }
        if (regionBlocks + volume > maxRegionBlocks) {
            throw new IllegalArgumentException(
                "snapshot region of " + volume
                    + " blocks is too big (at most "
                    + maxRegionBlocks
                    + " per script, undo.undoMaxRegionBlocks)");
        }
        regionBlocks += (int) volume;
        int n = 0;
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                for (int y = ay; y <= by; y++) {
                    if (saveBlock(world, x, y, z)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** An entity joined a world (Forge event). Records it when a journaled script spawned it. */
    static void onEntityJoin(EntityJoinWorldEvent event) {
        ChangeJournal j = active;
        Entity e = event.entity;
        // Entities of a chunk being loaded are already in that chunk (addedToChunk); a new one is added after the
        // event. Players are never recorded.
        if (j == null || e == null
            || event.world == null
            || event.world.isRemote
            || e.addedToChunk
            || e instanceof EntityPlayer) {
            return;
        }
        if (j.spawned.size() >= j.maxEntries) {
            return;
        }
        String kind = EntityList.getEntityString(e);
        j.spawned.add(
            new SnapshotData.Spawned(
                event.world.provider.dimensionId,
                e.getUniqueID()
                    .toString(),
                kind != null ? kind
                    : e.getClass()
                        .getSimpleName()));
    }

    /**
     * Closes the journal: replays the deferred block updates, turns capture off again, and returns the positions that
     * changed. Never throws.
     */
    public Result end() {
        try {
            for (int round = 0; round < REPLAY_ROUNDS; round++) {
                boolean any = false;
                for (Capture c : captures.values()) {
                    if (c.list.toNotify.isEmpty()) {
                        continue;
                    }
                    any = true;
                    List<BlockSnapshot> batch = new ArrayList<>(c.list.toNotify);
                    c.list.toNotify.clear();
                    for (BlockSnapshot s : batch) {
                        notify(c.world, s);
                    }
                }
                if (!any) {
                    break;
                }
            }
        } catch (Throwable t) {
            DaveBridge.LOG.warn("Undo journal: replaying block updates failed", t);
        }
        for (Capture c : captures.values()) {
            try {
                if (c.world.capturedBlockSnapshots != c.list || !c.world.captureBlockSnapshots) {
                    warn(
                        "something switched block capture off during the script (e.g. a simulated item use); "
                            + "later changes in dimension "
                            + c.world.provider.dimensionId
                            + " may be missing");
                }
                c.world.captureBlockSnapshots = c.oldCapture;
                c.world.capturedBlockSnapshots = c.oldList;
                for (BlockSnapshot s : c.list.toNotify) { // updates still deferred after the last round
                    notify(c.world, s);
                }
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Undo journal: restoring block capture failed", t);
            }
        }
        captures.clear();
        active = null;
        List<UndoEntry> changed = new ArrayList<>();
        for (UndoEntry e : entries.values()) {
            try {
                WorldServer w = DimensionManager.getWorld(e.dim);
                if (w == null) {
                    e.after(null, 0);
                    changed.add(e);
                    continue;
                }
                UndoEntry now = current(w, e.x, e.y, e.z);
                boolean same = now.block.equals(e.block) && now.meta == e.meta
                    && (now.tile == null ? e.tile == null : now.tile.equals(e.tile));
                if (!same) {
                    changed.add(e.after(now.block, now.meta));
                }
            } catch (Throwable t) {
                changed.add(e);
            }
        }
        return new Result(changed, new ArrayList<>(spawned), new ArrayList<>(warnings), partial);
    }

    private static void notify(World world, BlockSnapshot s) {
        try {
            Block now = world.getBlock(s.x, s.y, s.z);
            world.markAndNotifyBlock(s.x, s.y, s.z, null, s.replacedBlock, now, s.flag);
        } catch (Throwable t) {
            DaveBridge.LOG.warn("Undo journal: block update at {},{},{} failed", s.x, s.y, s.z, t);
        }
    }

    /** A position's state now, tile entity NBT included. */
    static UndoEntry current(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        NBTTagCompound tile = null;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te != null) {
            tile = new NBTTagCompound();
            te.writeToNBT(tile);
        }
        return new UndoEntry(world.provider.dimensionId, x, y, z, name(block), meta, tile);
    }
}
