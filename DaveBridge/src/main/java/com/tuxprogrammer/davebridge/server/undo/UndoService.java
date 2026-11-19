package com.tuxprogrammer.davebridge.server.undo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuxprogrammer.davebridge.DaveBridge;
import com.tuxprogrammer.davebridge.common.Config;
import com.tuxprogrammer.davebridge.server.DaveServer;
import com.tuxprogrammer.davebridge.server.bridge.MessageRouter;
import com.tuxprogrammer.davebridge.server.bridge.Protocol;
import com.tuxprogrammer.davebridge.server.bridge.ProtocolException;
import com.tuxprogrammer.davebridge.server.bridge.Request;
import com.tuxprogrammer.davebridge.server.chat.DaveChat;
import com.tuxprogrammer.davebridge.server.chat.markup.ChatStyles;
import com.tuxprogrammer.davebridge.server.command.TestHooks;
import com.tuxprogrammer.davebridge.server.eval.Confirms;
import com.tuxprogrammer.davebridge.server.eval.PendingConfirms;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Snapshots and undo (PROTOCOL.md 1.10): records confirmed write evals ({@link ChangeJournal}), lists them, and rolls
 * them back. An undo from the agent goes through the same in-game confirm as a write; {@code /dave undo} (the console,
 * an op, or the player whose change it was, e.g. from the [Undo] button) runs at once.
 */
public final class UndoService {

    /** Undos with at most this many positions run inside the command; bigger ones over the next ticks. */
    static final int SYNC_MAX_BLOCKS = 4096;

    private static volatile SnapshotStore store;
    private static final Deque<RestoreJob> JOBS = new ArrayDeque<>();

    private UndoService() {}

    public static void register(MessageRouter router) {
        router.register("snapshot_list", UndoService::handleList);
        router.register("snapshot_get", request -> handleGet(router, request));
        router.register("undo", UndoService::handleUndo);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    /** Records entities a journaled script spawns. */
    public static final class Events {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onEntityJoin(EntityJoinWorldEvent event) {
            try {
                if (ChangeJournal.active() != null && DaveServer.QUEUE.isMainThread()) {
                    ChangeJournal.onEntityJoin(event);
                }
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Undo journal: entity record failed", t);
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** After the worlds loaded: opens the snapshot store in the world folder. */
    public static void serverStarted() {
        try {
            File root = DimensionManager.getCurrentSaveRootDirectory();
            if (root == null) {
                DaveBridge.LOG.warn("Undo: no world folder; snapshots are off");
                return;
            }
            SnapshotStore s = new SnapshotStore(new File(new File(root, "davebridge"), "undo"));
            s.loadIndex();
            store = s;
        } catch (Throwable t) {
            DaveBridge.LOG.error("Undo: could not open the snapshot store", t);
        }
    }

    /** Finishes a running undo (the worlds are still loaded) and flushes the files. */
    public static void serverStopping() {
        synchronized (JOBS) {
            for (RestoreJob job : JOBS) {
                job.step(Long.MAX_VALUE);
                finish(job);
            }
            JOBS.clear();
        }
        SnapshotStore s = store;
        store = null;
        if (s != null) {
            s.close();
        }
    }

    /** True when snapshots are recorded: undo is on and the store is open. */
    public static boolean ready() {
        return Config.undoEnabled && store != null;
    }

    /** Server tick: runs a slice of the current undo. */
    public static void tick() {
        RestoreJob job;
        synchronized (JOBS) {
            job = JOBS.peek();
        }
        if (job == null) {
            return;
        }
        if (job.step(Math.max(1, Config.undoTickBudgetMillis) * 1_000_000L)) {
            synchronized (JOBS) {
                JOBS.poll();
            }
            finish(job);
        }
    }

    // ------------------------------------------------------------------ recording

    /**
     * Saves what a confirmed write changed as a snapshot. Server thread.
     *
     * @return the snapshot, or null when nothing changed (or snapshots are off)
     */
    public static SnapshotMeta record(ChangeJournal.Result result, String player, String summary, String script,
        String evalId, List<String> extraWarnings) {
        SnapshotStore s = store;
        if (s == null || result == null || result.isEmpty()) {
            return null;
        }
        SnapshotMeta m = new SnapshotMeta();
        m.id = s.index()
            .newId();
        m.kind = "eval";
        m.player = player;
        m.summary = summary;
        m.script = script == null ? null : script.length() > 2000 ? script.substring(0, 2000) + "..." : script;
        m.evalId = evalId;
        List<String> warnings = new ArrayList<>(result.warnings);
        if (extraWarnings != null) {
            warnings.addAll(extraWarnings);
        }
        fill(m, result.entries, result.spawned.size(), result.partial, warnings);
        s.save(m, new SnapshotData(result.entries, result.spawned), Config.undoMaxSnapshots, maxBytes(), maxAge());
        DaveBridge.LOG.info("Undo: snapshot {} for {}: {} blocks, {} entities", m.id, player, m.blocks, m.entities);
        return m;
    }

    private static void fill(SnapshotMeta m, List<UndoEntry> entries, int entities, boolean partial,
        List<String> warnings) {
        m.createdAt = System.currentTimeMillis();
        m.blocks = entries.size();
        m.entities = entities;
        m.partial = partial;
        m.warnings = warnings.isEmpty() ? null : warnings.toArray(new String[0]);
        java.util.TreeSet<Integer> dims = new java.util.TreeSet<>();
        int[] box = null;
        for (UndoEntry e : entries) {
            dims.add(e.dim);
            if (box == null) {
                box = new int[] { e.x, e.y, e.z, e.x, e.y, e.z };
            } else {
                box[0] = Math.min(box[0], e.x);
                box[1] = Math.min(box[1], e.y);
                box[2] = Math.min(box[2], e.z);
                box[3] = Math.max(box[3], e.x);
                box[4] = Math.max(box[4], e.y);
                box[5] = Math.max(box[5], e.z);
            }
        }
        m.dims = new int[dims.size()];
        int i = 0;
        for (int d : dims) {
            m.dims[i++] = d;
        }
        m.box = box;
    }

    private static long maxBytes() {
        return Config.undoMaxStorageMB * 1024L * 1024L;
    }

    private static long maxAge() {
        return Config.undoMaxAgeDays * 86_400_000L;
    }

    // ------------------------------------------------------------------ undo

    /** Resolves {@code u12}, {@code 12} or {@code last} (of {@code of}, or of anyone). */
    static SnapshotMeta resolve(String ref, String of) throws ProtocolException {
        SnapshotStore s = store;
        if (s == null) {
            throw new ProtocolException("disabled", "snapshots are not available (undo.undoEnabled, or no world)");
        }
        SnapshotMeta m = ref == null || "last".equalsIgnoreCase(ref.trim()) ? s.index()
            .last(of)
            : s.index()
                .get(ref);
        if (m == null) {
            throw new ProtocolException(
                Protocol.ERR_NOT_FOUND,
                ref == null || "last".equalsIgnoreCase(ref.trim())
                    ? "no snapshot to undo" + (of == null ? "" : " for " + of)
                    : "no snapshot " + ref);
        }
        return m;
    }

    /** Queues the restore; {@code onDone} runs on the server thread when it finishes. */
    private static RestoreJob start(SnapshotMeta m, SnapshotData data, boolean force, String by,
        java.util.function.Consumer<RestoreJob> onDone) {
        RestoreJob job = new RestoreJob(m, data, force, by, onDone);
        synchronized (JOBS) {
            JOBS.add(job);
        }
        DaveBridge.LOG.info("Undo {} started by {} ({} blocks{})", m.id, by, m.blocks, force ? ", forced" : "");
        return job;
    }

    /** Records the redo snapshot, marks the original undone and reports. Server thread. */
    private static void finish(RestoreJob job) {
        try {
            SnapshotStore s = store;
            if (s != null && !job.redo.isEmpty()) {
                SnapshotMeta r = new SnapshotMeta();
                r.id = s.index()
                    .newId();
                r.kind = "undo";
                r.player = job.by;
                r.summary = "undo of " + job.meta.id + ": " + job.meta.summary;
                r.undoOf = job.meta.id;
                fill(r, job.redo, 0, false, new ArrayList<>());
                job.redoId = r.id;
                job.meta.undoneAt = System.currentTimeMillis();
                job.meta.undoneBy = job.by;
                job.meta.redo = r.id;
                s.save(r, new SnapshotData(job.redo, new ArrayList<>()), Config.undoMaxSnapshots, maxBytes(), maxAge());
            } else if (s != null && job.restored == 0 && job.removedEntities > 0) {
                job.meta.undoneAt = System.currentTimeMillis();
                job.meta.undoneBy = job.by;
                s.saveIndex();
            }
            DaveBridge.LOG.info("{}", job.describe());
        } catch (Throwable t) {
            DaveBridge.LOG.warn("Undo {}: finishing failed", job.meta.id, t);
        }
        try {
            job.onDone.accept(job);
        } catch (Throwable t) {
            DaveBridge.LOG.warn("Undo {}: reporting failed", job.meta.id, t);
        }
    }

    // ------------------------------------------------------------------ agent requests

    private static void handleList(Request request) throws ProtocolException {
        SnapshotStore s = store;
        if (s == null) {
            throw new ProtocolException("disabled", "snapshots are not available (undo.undoEnabled, or no world)");
        }
        String player = request.optString("player", null);
        int limit = (int) Math.max(1, Math.min(200, optLong(request.body(), "limit", 20)));
        JsonObject reply = Protocol.message("snapshots");
        JsonArray list = new JsonArray();
        for (SnapshotMeta m : s.index()
            .list(player, limit)) {
            list.add(m.toJson());
        }
        reply.add("snapshots", list);
        reply.addProperty(
            "total",
            s.index()
                .size());
        request.reply(reply);
    }

    /** A snapshot's metadata plus a preview: the first changes, and how many loaded positions changed since. */
    private static void handleGet(MessageRouter router, Request request) throws ProtocolException {
        String ref = request.requireString("snapshot");
        SnapshotMeta m = resolve(ref, request.optString("of", null));
        SnapshotData data;
        try {
            data = store.load(m.id);
        } catch (IOException e) {
            throw new ProtocolException(Protocol.ERR_NOT_FOUND, e.getMessage());
        }
        router.runOnMainThread(request, "snapshot_get", () -> {
            JsonObject reply = Protocol.message("snapshot");
            JsonObject snap = m.toJson();
            JsonArray changes = new JsonArray();
            int conflicts = 0, unloaded = 0;
            for (int i = 0; i < data.entries.size(); i++) {
                UndoEntry e = data.entries.get(i);
                if (i < 30) {
                    JsonObject c = new JsonObject();
                    c.addProperty("dim", e.dim);
                    c.addProperty("x", e.x);
                    c.addProperty("y", e.y);
                    c.addProperty("z", e.z);
                    c.addProperty("before", e.block + (e.meta == 0 ? "" : ":" + e.meta));
                    if (e.tile != null) {
                        c.addProperty("beforeTile", e.tile.getString("id"));
                    }
                    if (e.afterBlock != null) {
                        c.addProperty("after", e.afterBlock + (e.afterMeta == 0 ? "" : ":" + e.afterMeta));
                    }
                    changes.add(c);
                }
                WorldServer w = DimensionManager.getWorld(e.dim);
                if (w == null || !w.getChunkProvider()
                    .chunkExists(e.x >> 4, e.z >> 4)) {
                    unloaded++;
                    continue;
                }
                if (e.afterBlock != null && (!ChangeJournal.name(w.getBlock(e.x, e.y, e.z))
                    .equals(e.afterBlock) || w.getBlockMetadata(e.x, e.y, e.z) != e.afterMeta)) {
                    conflicts++;
                }
            }
            snap.add("changes", changes);
            snap.addProperty("changedSince", conflicts);
            snap.addProperty("unloaded", unloaded);
            reply.add("snapshot", snap);
            return reply;
        });
    }

    /**
     * {@code undo}: shows the asking player what would be restored, with Confirm and Cancel; on confirm restores it
     * over the next ticks and answers {@code undo_result}.
     */
    private static void handleUndo(Request request) throws ProtocolException {
        if (!Config.undoEnabled) {
            throw new ProtocolException("disabled", "undo is disabled on this server (undo.undoEnabled)");
        }
        String playerName = request.optString("player", null);
        if (playerName == null || playerName.trim()
            .isEmpty()) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "undo needs \"player\": who confirms it in chat");
        }
        String ref = request.optString("snapshot", "last");
        String of = request.optString("of", null);
        boolean force = request.body()
            .has("force")
            && request.body()
                .get("force")
                .getAsBoolean();
        long seconds = Math.max(
            5,
            Math.min(
                Config.confirmMaxTimeoutSeconds,
                optLong(request.body(), "confirmTimeoutSeconds", Config.confirmTimeoutSeconds)));
        // "last" means the asking player's last change unless "of" names someone else ("*": anyone's).
        String whose = of == null ? playerName : "*".equals(of.trim()) ? null : of;
        SnapshotMeta m = resolve(ref, whose);
        if (m.undoneAt != 0 && !force) {
            throw new ProtocolException(
                Protocol.ERR_BAD_REQUEST,
                m.id + " was already undone by "
                    + m.undoneBy
                    + (m.redo == null ? "" : "; undo " + m.redo + " to redo it")
                    + " (force:true undoes it again)");
        }
        SnapshotData data;
        try {
            data = store.load(m.id);
        } catch (IOException e) {
            throw new ProtocolException(Protocol.ERR_NOT_FOUND, e.getMessage());
        }
        DaveServer.QUEUE.submit("undo_confirm", () -> {
            EntityPlayerMP player = findPlayer(playerName);
            String name = player != null ? player.getCommandSenderName() : playerName;
            UUID uuid = player != null ? player.getUniqueID() : TestHooks.uuidFor(playerName);
            UndoJob job = new UndoJob(request, m, data, force);
            PendingConfirms.Pending<Confirms.Job> pending = Confirms.create(player, name, uuid, seconds, job);
            if (pending == null) {
                request
                    .reply(result(m, false, "too_many_pending", name + " already has changes waiting for a confirm"));
                return;
            }
            JsonObject event = Protocol.message("undo_pending");
            event.addProperty("snapshot", m.id);
            event.addProperty("player", name);
            event.addProperty("playerOnline", player != null);
            event.addProperty("confirmId", pending.token);
            event.addProperty("summary", job.summary());
            event.addProperty("expiresInSeconds", seconds);
            request.session()
                .send(event);
            DaveBridge.LOG.info("Undo {} waits for {} to confirm ({})", m.id, name, pending.token);
        })
            .whenComplete((v, failure) -> {
                if (failure != null) {
                    request.error(Protocol.ERR_QUEUE_FULL, String.valueOf(failure.getMessage()));
                }
            });
    }

    private static JsonObject result(SnapshotMeta m, boolean ok, String errorType, String error) {
        JsonObject r = Protocol.message("undo_result");
        r.addProperty("snapshot", m.id);
        r.addProperty("ok", ok);
        if (errorType != null) {
            r.addProperty("errorType", errorType);
            r.addProperty("error", error);
        }
        return r;
    }

    /** An agent's undo, waiting for the player's click. */
    private static final class UndoJob implements Confirms.Job {

        final Request request;
        final SnapshotMeta meta;
        final SnapshotData data;
        final boolean force;

        UndoJob(Request request, SnapshotMeta meta, SnapshotData data, boolean force) {
            this.request = request;
            this.meta = meta;
            this.data = data;
            this.force = force;
        }

        @Override
        public String summary() {
            String s = meta.summary == null ? "" : meta.summary;
            if (s.length() > 200) {
                s = s.substring(0, 200) + "...";
            }
            return s + " (" + meta.id + ", " + meta.blocks + " block" + (meta.blocks == 1 ? "" : "s") + ")";
        }

        @Override
        public String headline() {
            return "wants to undo: ";
        }

        @Override
        public String hover() {
            return "Puts back " + meta.blocks
                + " block position(s)"
                + (meta.entities > 0 ? " and removes " + meta.entities + " spawned entities" : "")
                + " changed by "
                + meta.player
                + (force ? ".\nForced: also positions changed since." : ".");
        }

        @Override
        public void confirmed(Confirms.Confirmed by) {
            start(meta, data, force, by.asker, job -> {
                JsonObject r = job.toJson();
                r.addProperty("type", "undo_result");
                r.addProperty("ts", System.currentTimeMillis());
                r.addProperty("confirmed", true);
                r.addProperty("confirmedBy", by.who);
                request.reply(r);
                EntityPlayerMP p = findPlayer(by.asker);
                if (p != null) {
                    p.addChatMessage(DaveChat.notice(job.describe()));
                }
                if (by.clicker == null) {
                    by.sender.addChatMessage(DaveChat.notice(job.describe()));
                }
            });
        }

        @Override
        public void cancelled(String who) {
            request.reply(result(meta, false, "cancelled", who + " clicked Cancel; nothing was undone"));
        }

        @Override
        public void expired(String asker) {
            request.reply(result(meta, false, "expired", asker + " did not confirm in time; nothing was undone"));
        }

        @Override
        public void stopping() {
            request.error(Protocol.ERR_CANCELLED, "server stopping before the confirm");
        }
    }

    // ------------------------------------------------------------------ commands

    /** {@code /dave snapshots [player]}. */
    public static List<String> describeList(String player, int limit) {
        List<String> out = new ArrayList<>();
        SnapshotStore s = store;
        if (s == null) {
            out.add("Snapshots are off (undo.undoEnabled) or the world is not loaded.");
            return out;
        }
        long now = System.currentTimeMillis();
        for (SnapshotMeta m : s.index()
            .list(player, limit)) {
            out.add(m.describe(now));
        }
        out.add(
            0,
            s.index()
                .size() + " snapshot(s), "
                + (s.index()
                    .totalBytes() / 1024)
                + " KiB on disk"
                + (player == null ? "" : "; showing " + player + "'s"));
        return out;
    }

    /**
     * {@code /dave undo <id|last> [player] [force]}: the console, an op, or the player whose change it was. Runs at
     * once (typing it or clicking [Undo] is the confirmation). Server thread.
     */
    public static IChatComponent command(ICommandSender sender, boolean op, String ref, String of, boolean force) {
        try {
            if (!Config.undoEnabled) {
                return DaveChat.notice("Undo is disabled on this server.");
            }
            String who = sender.getCommandSenderName();
            SnapshotMeta m = resolve(ref, op ? of : who);
            if (!op && !who.equalsIgnoreCase(m.player)) {
                return DaveChat.notice("Only " + m.player + " (or an op) can undo " + m.id + ".");
            }
            if (m.undoneAt != 0 && !force) {
                return DaveChat.notice(
                    m.id + " was already undone"
                        + (m.redo == null ? "." : "; /dave undo " + m.redo + " redoes it.")
                        + " Add force to undo it again.");
            }
            synchronized (JOBS) {
                for (RestoreJob j : JOBS) {
                    if (j.meta == m) {
                        return DaveChat.notice(m.id + " is being undone right now.");
                    }
                }
            }
            SnapshotData data = store.load(m.id);
            boolean console = !(sender instanceof EntityPlayerMP);
            boolean sync = data.entries.size() <= SYNC_MAX_BLOCKS;
            RestoreJob job = new RestoreJob(m, data, force, who, j -> {
                if (!sync && !console) {
                    sender.addChatMessage(DaveChat.notice(j.describe()));
                }
            });
            if (sync) {
                job.step(Long.MAX_VALUE);
                finish(job);
                appendWarnings(sender, job);
                return DaveChat.notice(job.describe());
            }
            synchronized (JOBS) {
                JOBS.add(job);
            }
            return DaveChat
                .notice("Undoing " + m.id + " (" + m.blocks + " blocks) over the next ticks; see /dave snapshots.");
        } catch (ProtocolException e) {
            return DaveChat.notice(e.getMessage());
        } catch (IOException e) {
            return DaveChat.notice("Cannot read that snapshot: " + e.getMessage());
        } catch (Throwable t) {
            DaveBridge.LOG.warn("/dave undo failed", t);
            return DaveChat.notice("Undo failed: " + t);
        }
    }

    private static void appendWarnings(ICommandSender sender, RestoreJob job) {
        int n = 0;
        for (String w : job.warnings) {
            if (n++ >= 5) {
                break;
            }
            sender.addChatMessage(DaveChat.notice(" " + w));
        }
    }

    /** The [Undo] button after a confirmed change. */
    public static IChatComponent undoButton(SnapshotMeta m) {
        return ChatStyles.button(
            "Undo",
            EnumChatFormatting.YELLOW,
            null,
            ChatStyles.hoverText(
                "Put back the " + m.blocks + " block position(s) this change touched (snapshot " + m.id + ")"),
            ChatStyles.run("/dave undo " + m.id));
    }

    // ------------------------------------------------------------------ helpers

    static EntityPlayerMP findPlayer(String name) {
        if (name == null) {
            return null;
        }
        for (EntityPlayerMP p : DaveChat.onlinePlayers()) {
            if (p.getCommandSenderName()
                .equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    static long optLong(JsonObject body, String key, long def) throws ProtocolException {
        JsonElement el = body.get(key);
        if (el == null || el.isJsonNull()) {
            return def;
        }
        try {
            return el.getAsLong();
        } catch (RuntimeException e) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "'" + key + "' must be a number");
        }
    }

}
