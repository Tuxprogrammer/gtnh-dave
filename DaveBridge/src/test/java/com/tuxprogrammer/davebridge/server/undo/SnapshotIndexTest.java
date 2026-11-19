package com.tuxprogrammer.davebridge.server.undo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

class SnapshotIndexTest {

    static SnapshotMeta meta(SnapshotIndex index, String player, long at, long bytes) {
        SnapshotMeta m = new SnapshotMeta();
        m.id = index.newId();
        m.kind = "eval";
        m.player = player;
        m.summary = "change " + m.id;
        m.createdAt = at;
        m.bytes = bytes;
        index.add(m);
        return m;
    }

    @Test
    void lookupAndLast() {
        SnapshotIndex idx = new SnapshotIndex();
        SnapshotMeta a = meta(idx, "Steve", 1, 10);
        SnapshotMeta b = meta(idx, "Alex", 2, 10);
        SnapshotMeta c = meta(idx, "steve", 3, 10);
        assertEquals("u1", a.id);
        assertEquals(b, idx.get("2"));
        assertEquals(b, idx.get("U2"));
        assertNull(idx.get("u9"));
        assertEquals(c, idx.last("STEVE"));
        assertEquals(c, idx.last(null));
        c.undoneAt = 5;
        assertEquals(a, idx.last("Steve"), "undone snapshots are skipped");
        assertEquals(Arrays.asList(c, a), idx.list("steve", 10), "newest first");
        assertEquals(Collections.singletonList(c), idx.list(null, 1));
    }

    @Test
    void pruneByCountSizeAndAge() {
        SnapshotIndex idx = new SnapshotIndex();
        for (int i = 0; i < 5; i++) {
            meta(idx, "A", 1000 + i, 100);
        }
        List<SnapshotMeta> gone = idx.prune(3, 10_000, 0, 2000);
        assertEquals(2, gone.size());
        assertEquals("u1", gone.get(0).id, "oldest first");
        gone = idx.prune(10, 150, 0, 2000);
        assertEquals(2, gone.size(), "down to 100 bytes");
        assertEquals(1, idx.size());
        gone = idx.prune(10, 10, 0, 2000);
        assertTrue(gone.isEmpty(), "the newest is never removed for size alone");
        gone = idx.prune(10, 10_000, 500, 2000);
        assertEquals(1, gone.size(), "but it does age out");
    }

    @Test
    void persistsAndKeepsIds() {
        SnapshotIndex idx = new SnapshotIndex();
        SnapshotMeta a = meta(idx, "A", 1, 10);
        a.box = new int[] { 1, 2, 3, 4, 5, 6 };
        a.undoneAt = 9;
        a.redo = "u2";
        SnapshotIndex back = new SnapshotIndex();
        back.load(idx.toJson());
        assertArrayEquals(a.box, back.get("u1").box);
        assertEquals("u2", back.get("u1").redo);
        assertEquals("u2", back.newId());
        SnapshotIndex odd = new SnapshotIndex();
        odd.load("{\"nextId\":1,\"snapshots\":[{\"id\":\"u41\"},null]}");
        assertEquals("u42", odd.newId(), "nextId moves past stored ids");
        assertThrows(IllegalArgumentException.class, () -> new SnapshotIndex().load("{nope"));
    }

    @Test
    void dataRoundTrip() throws Exception {
        NBTTagCompound chest = new NBTTagCompound();
        chest.setString("id", "Chest");
        chest.setInteger("x", 1);
        UndoEntry e1 = new UndoEntry(0, 1, 64, -2, "minecraft:chest", 3, chest).after("minecraft:air", 0);
        UndoEntry e2 = new UndoEntry(-1, 5, 10, 5, "minecraft:stone", 0, null);
        SnapshotData d = new SnapshotData(
            Arrays.asList(e1, e2),
            Collections.singletonList(new SnapshotData.Spawned(0, "uuid-1", "Item")));
        SnapshotData back = SnapshotData.decode(d.encode("u7"));
        assertEquals(2, back.entries.size());
        UndoEntry b1 = back.entries.get(0);
        assertEquals("minecraft:chest", b1.block);
        assertEquals(3, b1.meta);
        assertEquals("minecraft:air", b1.afterBlock);
        assertEquals(chest, b1.tile);
        assertEquals(-2, b1.z);
        UndoEntry b2 = back.entries.get(1);
        assertNull(b2.tile);
        assertNull(b2.afterBlock);
        assertEquals(-1, b2.dim);
        assertEquals("uuid-1", back.spawned.get(0).uuid);
        assertEquals("Item", back.spawned.get(0).kind);
    }
}
