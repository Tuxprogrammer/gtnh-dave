package com.tuxprogrammer.davebridge.server.undo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * The body of a snapshot: the changed block positions in the order they were first touched, and the entities the
 * change spawned. Stored as gzipped NBT ({@code <id>.dat}); the metadata lives in the index ({@link SnapshotMeta}).
 */
public final class SnapshotData {

    /** An entity the change spawned (an item drop, a mob): removed again on undo if it still exists. */
    public static final class Spawned {

        public final int dim;
        public final String uuid;
        public final String kind;

        public Spawned(int dim, String uuid, String kind) {
            this.dim = dim;
            this.uuid = uuid;
            this.kind = kind;
        }
    }

    public static final int FORMAT = 1;

    public final List<UndoEntry> entries;
    public final List<Spawned> spawned;

    public SnapshotData(List<UndoEntry> entries, List<Spawned> spawned) {
        this.entries = entries;
        this.spawned = spawned;
    }

    public NBTTagCompound toNbt(String id) {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("v", FORMAT);
        root.setString("id", id);
        NBTTagList list = new NBTTagList();
        for (UndoEntry e : entries) {
            list.appendTag(e.toNbt());
        }
        root.setTag("e", list);
        NBTTagList ents = new NBTTagList();
        for (Spawned s : spawned) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("d", s.dim);
            t.setString("u", s.uuid);
            t.setString("k", s.kind == null ? "" : s.kind);
            ents.appendTag(t);
        }
        root.setTag("n", ents);
        return root;
    }

    public static SnapshotData fromNbt(NBTTagCompound root) throws IOException {
        if (root.getInteger("v") != FORMAT) {
            throw new IOException("unknown snapshot format " + root.getInteger("v"));
        }
        List<UndoEntry> entries = new ArrayList<>();
        NBTTagList list = root.getTagList("e", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            entries.add(UndoEntry.fromNbt(list.getCompoundTagAt(i)));
        }
        List<Spawned> spawned = new ArrayList<>();
        NBTTagList ents = root.getTagList("n", 10);
        for (int i = 0; i < ents.tagCount(); i++) {
            NBTTagCompound t = ents.getCompoundTagAt(i);
            spawned.add(new Spawned(t.getInteger("d"), t.getString("u"), t.getString("k")));
        }
        return new SnapshotData(entries, spawned);
    }

    public byte[] encode(String id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(toNbt(id), bytes);
        return bytes.toByteArray();
    }

    public static SnapshotData decode(byte[] gz) throws IOException {
        return fromNbt(CompressedStreamTools.readCompressed(new ByteArrayInputStream(gz)));
    }
}
