package com.tuxprogrammer.davebridge.server.undo;

import net.minecraft.nbt.NBTTagCompound;

/**
 * One block position of a snapshot: what was there before the change (block registry name, metadata, tile entity
 * NBT) and what the change left there (block and metadata, to spot later edits before an undo). Only NBT types, so
 * it is unit-tested directly.
 */
public final class UndoEntry {

    public final int dim;
    public final int x;
    public final int y;
    public final int z;
    /** Registry name before the change, e.g. {@code minecraft:chest}. */
    public final String block;
    public final int meta;
    /** The tile entity's NBT before the change, or null when there was none. */
    public final NBTTagCompound tile;
    /** Registry name after the change (null until the journal closes). */
    public String afterBlock;
    public int afterMeta;

    public UndoEntry(int dim, int x, int y, int z, String block, int meta, NBTTagCompound tile) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
        this.meta = meta;
        this.tile = tile;
    }

    public UndoEntry after(String block, int meta) {
        this.afterBlock = block;
        this.afterMeta = meta;
        return this;
    }

    /** The map key of a position. */
    public static String key(int dim, int x, int y, int z) {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    public String key() {
        return key(dim, x, y, z);
    }

    public String pos() {
        return x + "," + y + "," + z + (dim == 0 ? "" : " (dim " + dim + ")");
    }

    NBTTagCompound toNbt() {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("d", dim);
        t.setInteger("x", x);
        t.setInteger("y", y);
        t.setInteger("z", z);
        t.setString("b", block);
        t.setInteger("m", meta);
        if (tile != null) {
            t.setTag("t", tile);
        }
        if (afterBlock != null) {
            t.setString("ab", afterBlock);
            t.setInteger("am", afterMeta);
        }
        return t;
    }

    static UndoEntry fromNbt(NBTTagCompound t) {
        UndoEntry e = new UndoEntry(
            t.getInteger("d"),
            t.getInteger("x"),
            t.getInteger("y"),
            t.getInteger("z"),
            t.getString("b"),
            t.getInteger("m"),
            t.hasKey("t") ? t.getCompoundTag("t") : null);
        if (t.hasKey("ab")) {
            e.after(t.getString("ab"), t.getInteger("am"));
        }
        return e;
    }
}
