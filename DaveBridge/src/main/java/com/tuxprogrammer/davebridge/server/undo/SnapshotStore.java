package com.tuxprogrammer.davebridge.server.undo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.tuxprogrammer.davebridge.DaveBridge;

/**
 * Snapshots on disk, under the world folder ({@code <world>/davebridge/undo/}): {@code index.json} plus one
 * gzipped NBT file per snapshot ({@code u12.dat}). Files are written on a background thread, so a large snapshot
 * never holds the server tick; the newest snapshots stay in memory, so an undo right after a change does not wait for
 * its file. Thread-safe.
 */
public final class SnapshotStore {

    private static final int CACHE = 8;

    private final File dir;
    private final SnapshotIndex index = new SnapshotIndex();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DaveBridge-undo-io");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, SnapshotData> cache = new LinkedHashMap<String, SnapshotData>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SnapshotData> eldest) {
            return size() > CACHE;
        }
    };

    public SnapshotStore(File dir) {
        this.dir = dir;
    }

    public SnapshotIndex index() {
        return index;
    }

    public File dir() {
        return dir;
    }

    /** Reads {@code index.json}; a missing file is an empty index, an unreadable one is kept aside and logged. */
    public void loadIndex() {
        File f = new File(dir, "index.json");
        if (!f.isFile()) {
            return;
        }
        try {
            index.load(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            DaveBridge.LOG.info("Undo: {} snapshot(s) in {}", index.size(), dir);
        } catch (IOException | RuntimeException e) {
            File aside = new File(dir, "index.json.unreadable-" + System.currentTimeMillis());
            DaveBridge.LOG.error("Undo: could not read {} ({}); moved it to {}", f, e.toString(), aside.getName());
            f.renameTo(aside);
        }
    }

    /**
     * Adds a snapshot: indexed and cached now, its file written in the background, then the index pruned to the
     * limits (and the pruned snapshots' files deleted).
     */
    public void save(SnapshotMeta meta, SnapshotData data, int maxCount, long maxBytes, long maxAgeMillis) {
        synchronized (cache) {
            cache.put(meta.id, data);
        }
        index.add(meta);
        io.execute(() -> {
            try {
                byte[] bytes = data.encode(meta.id);
                write(new File(dir, meta.id + ".dat"), bytes);
                meta.bytes = bytes.length;
            } catch (Throwable t) {
                DaveBridge.LOG.error("Undo: could not write snapshot {}", meta.id, t);
            }
            List<SnapshotMeta> removed = index.prune(maxCount, maxBytes, maxAgeMillis, System.currentTimeMillis());
            for (SnapshotMeta m : removed) {
                synchronized (cache) {
                    cache.remove(m.id);
                }
                new File(dir, m.id + ".dat").delete();
                DaveBridge.LOG.info("Undo: pruned snapshot {} ({})", m.id, m.summary);
            }
            writeIndexNow();
        });
    }

    /** Saves the index in the background (after a change to a snapshot's metadata, e.g. marked undone). */
    public void saveIndex() {
        io.execute(this::writeIndexNow);
    }

    private void writeIndexNow() {
        try {
            write(
                new File(dir, "index.json"),
                index.toJson()
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            DaveBridge.LOG.error("Undo: could not write the snapshot index", t);
        }
    }

    /** A snapshot's body, from memory or disk. Any thread. */
    public SnapshotData load(String id) throws IOException {
        synchronized (cache) {
            SnapshotData d = cache.get(id);
            if (d != null) {
                return d;
            }
        }
        File f = new File(dir, id + ".dat");
        if (!f.isFile()) {
            throw new IOException("snapshot " + id + " has no data file (deleted, or not written yet)");
        }
        SnapshotData d = SnapshotData.decode(Files.readAllBytes(f.toPath()));
        synchronized (cache) {
            cache.put(id, d);
        }
        return d;
    }

    /** Waits for pending writes (server stopping). */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(20, TimeUnit.SECONDS)) {
                DaveBridge.LOG.warn("Undo: snapshot writes did not finish in 20 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    /** Atomic: a temp file renamed over the target, so a crash never leaves half a file. */
    static void write(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        Files.write(tmp.toPath(), bytes);
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
