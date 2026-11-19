package com.tuxprogrammer.davebridge.server.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tuxprogrammer.davebridge.DaveBridge;
import com.tuxprogrammer.davebridge.common.Config;
import com.tuxprogrammer.davebridge.common.eval.EvalHelpers;
import com.tuxprogrammer.davebridge.common.eval.JsonDump;
import com.tuxprogrammer.davebridge.common.eval.ScriptEngine;
import com.tuxprogrammer.davebridge.server.DaveServer;
import com.tuxprogrammer.davebridge.server.bridge.MessageRouter;
import com.tuxprogrammer.davebridge.server.bridge.Protocol;
import com.tuxprogrammer.davebridge.server.bridge.ProtocolException;
import com.tuxprogrammer.davebridge.server.bridge.Request;
import com.tuxprogrammer.davebridge.server.chat.DaveChat;
import com.tuxprogrammer.davebridge.server.command.TestHooks;
import com.tuxprogrammer.davebridge.server.undo.ChangeJournal;
import com.tuxprogrammer.davebridge.server.undo.ScriptUndo;
import com.tuxprogrammer.davebridge.server.undo.SnapshotMeta;
import com.tuxprogrammer.davebridge.server.undo.UndoService;

/**
 * Agent requests for live eval (PROTOCOL.md 1.8): {@code eval} and {@code describe}.
 * <p>
 * An {@code eval} is compiled on a background thread, then run on the server thread through the main-thread queue,
 * so it sees a consistent world and its time limit bounds how long it holds the tick. A {@code mode:"write"} script
 * first waits for the asking player to click Confirm in chat ({@link Confirms}); a {@code mode:"read"} script that
 * obviously changes the world is refused with {@code needs_confirm}. A confirmed write runs inside a change journal
 * ({@link ChangeJournal}), so it can be undone. Script failures are reported as {@code eval_result} with
 * {@code ok:false}; they never reach the server.
 */
public final class EvalService {

    static final int MAX_SCRIPT_CHARS = 64 * 1024;
    static final int MAX_SUMMARY_CHARS = 300;
    static final int MAX_CONFIRMS_PER_PLAYER = Confirms.MAX_PER_PLAYER;
    static final int MAX_REGIONS = 16;

    /** Mutators the block journal sees (World.setBlock and what calls it); the others need an explicit save. */
    static final Set<String> JOURNALED = new HashSet<>(
        Arrays.asList(
            "setBlock",
            "func_147449_b",
            "func_147465_d",
            "setBlockToAir",
            "func_147468_f",
            "func_147480_a",
            "breakBlock"));

    private static final Gson GSON = new Gson();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DaveBridge-eval-timer");
        t.setDaemon(true);
        return t;
    });

    private EvalService() {}

    /** A cuboid to snapshot before a write runs (the request's {@code snapshot}). */
    static final class Region {

        final int dim;
        final int[] from;
        final int[] to;

        Region(int dim, int[] from, int[] to) {
            this.dim = dim;
            this.from = from;
            this.to = to;
        }

        long volume() {
            return (long) (Math.abs(to[0] - from[0]) + 1) * (Math.abs(to[1] - from[1]) + 1)
                * (Math.abs(to[2] - from[2]) + 1);
        }
    }

    /** A write-mode script waiting for its confirm. */
    static final class WriteJob implements Confirms.Job {

        final Request request;
        final String id;
        final ScriptEngine.Compiled compiled;
        final Object params;
        final long timeoutMs;
        final String summary;
        final String script;
        final List<Region> regions;

        WriteJob(Request request, String id, ScriptEngine.Compiled compiled, Object params, long timeoutMs,
            String summary, String script, List<Region> regions) {
            this.request = request;
            this.id = id;
            this.compiled = compiled;
            this.params = params;
            this.timeoutMs = timeoutMs;
            this.summary = summary;
            this.script = script;
            this.regions = regions;
        }

        @Override
        public String summary() {
            return summary;
        }

        @Override
        public String headline() {
            return "wants to change the world: ";
        }

        @Override
        public String hover() {
            return "Script:\n" + clip(script, 800);
        }

        @Override
        public void confirmed(Confirms.Confirmed by) {
            runBackupCommand();
            Ran ran = runConfirmed(this, by.asker);
            JsonObject r = ran.result;
            r.addProperty("confirmedBy", by.who);
            request.reply(r);
            boolean ok = r.has("ok") && r.get("ok")
                .getAsBoolean();
            String outcome = ok ? "Done."
                : "That change failed: " + clip(
                    r.get("error")
                        .getAsString(),
                    200);
            SnapshotMeta snap = ran.snapshot;
            EntityPlayerMP askerPlayer = findPlayer(by.asker);
            if (askerPlayer != null) {
                IChatComponent line = DaveChat.notice(outcome);
                if (snap != null) {
                    line.appendSibling(new ChatComponentText(" "));
                    line.appendSibling(UndoService.undoButton(snap));
                }
                askerPlayer.addChatMessage(line);
            }
            if (by.clicker == null) {
                by.sender.addChatMessage(
                    DaveChat.notice(
                        "Eval " + by.token
                            + ": "
                            + outcome
                            + (snap == null ? "" : " Snapshot " + snap.id + " (/dave undo " + snap.id + ")")));
            }
        }

        @Override
        public void cancelled(String who) {
            JsonObject r = result(id, "write");
            r.addProperty("ok", false);
            r.addProperty("errorType", "cancelled");
            r.addProperty("error", who + " clicked Cancel; nothing was run");
            request.reply(r);
        }

        @Override
        public void expired(String asker) {
            JsonObject r = result(id, "write");
            r.addProperty("ok", false);
            r.addProperty("errorType", "expired");
            r.addProperty("error", asker + " did not confirm in time; nothing was run");
            request.reply(r);
        }

        @Override
        public void stopping() {
            request.error(Protocol.ERR_CANCELLED, "server stopping before the confirm");
        }
    }

    public static void register(MessageRouter router) {
        EvalHelpers.registerAdapters();
        router.register("eval", request -> handleEval(router, request));
        router.register("describe", request -> handleDescribe(router, request));
        ClientEvalRelay.register(router);
    }

    // ------------------------------------------------------------------ eval

    private static void handleEval(MessageRouter router, Request request) throws ProtocolException {
        if (!Config.evalEnabled) {
            throw new ProtocolException("disabled", "eval is disabled on this server (eval.evalEnabled)");
        }
        String script = request.requireString("script");
        if (script.length() > MAX_SCRIPT_CHARS) {
            throw new ProtocolException(
                Protocol.ERR_BAD_REQUEST,
                "script too long (" + script.length() + " chars, at most " + MAX_SCRIPT_CHARS + ")");
        }
        String id = request.optString("id", null);
        String mode = request.optString("mode", "read")
            .toLowerCase(Locale.ROOT);
        if (!"read".equals(mode) && !"write".equals(mode)) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "mode must be \"read\" or \"write\"");
        }
        String playerName = request.optString("player", null);
        String summary = request.optString("summary", null);
        long timeoutMs = clampTimeout(optLong(request.body(), "timeoutMs", Config.evalTimeoutMillis));
        long confirmSeconds = Math.max(
            5,
            Math.min(
                Config.confirmMaxTimeoutSeconds,
                optLong(request.body(), "confirmTimeoutSeconds", Config.confirmTimeoutSeconds)));
        Object params = toJava(
            request.body()
                .get("params"));
        List<Region> regions = parseRegions(
            request.body()
                .get("snapshot"));
        if ("write".equals(mode)) {
            if (playerName == null) {
                throw new ProtocolException(
                    Protocol.ERR_BAD_REQUEST,
                    "mode \"write\" needs \"player\": the asking player, who confirms it in chat");
            }
            if (summary == null || summary.trim()
                .isEmpty()) {
                throw new ProtocolException(
                    Protocol.ERR_BAD_REQUEST,
                    "mode \"write\" needs \"summary\": one line telling the player what will change");
            }
        } else if (!regions.isEmpty()) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "\"snapshot\" is for mode \"write\" only");
        }
        String summaryText = summary == null ? null : clip(summary.trim(), MAX_SUMMARY_CHARS);

        ScriptEngine.shared()
            .compileAsync(script)
            .whenComplete((compiled, failure) -> {
                try {
                    if (failure != null) {
                        request.reply(compileFailure(id, mode, failure));
                        return;
                    }
                    if ("read".equals(mode) && !compiled.mutators.isEmpty()) {
                        JsonObject r = result(id, mode);
                        r.addProperty("ok", false);
                        r.addProperty("errorType", "needs_confirm");
                        r.addProperty(
                            "error",
                            "this looks like it changes the world (" + String.join(", ", compiled.mutators)
                                + "). Send it again with mode \"write\" and a summary; the player confirms it in chat.");
                        request.reply(r);
                        return;
                    }
                    if ("write".equals(mode)) {
                        WriteJob job = new WriteJob(
                            request,
                            id,
                            compiled,
                            params,
                            timeoutMs,
                            summaryText,
                            script,
                            regions);
                        onMain(request, "eval_confirm", () -> askToConfirm(job, playerName, confirmSeconds));
                        return;
                    }
                    router.runOnMainThread(
                        request,
                        "eval",
                        () -> runNow(id, mode, compiled, params, timeoutMs, playerName, false, null));
                } catch (Throwable t) {
                    DaveBridge.LOG.warn("eval request failed", t);
                    request.error(Protocol.ERR_INTERNAL, t.toString());
                }
            });
    }

    /** {@code snapshot}: one region object or an array of them, {@code {"dim":0,"from":[x,y,z],"to":[x,y,z]}}. */
    static List<Region> parseRegions(JsonElement el) throws ProtocolException {
        List<Region> out = new ArrayList<>();
        if (el == null || el.isJsonNull()) {
            return out;
        }
        JsonArray list;
        if (el.isJsonArray()) {
            list = el.getAsJsonArray();
        } else if (el.isJsonObject()) {
            list = new JsonArray();
            list.add(el);
        } else {
            throw new ProtocolException(
                Protocol.ERR_BAD_REQUEST,
                "\"snapshot\" must be {\"dim\":0,\"from\":[x,y,z],\"to\":[x,y,z]} or an array of them");
        }
        if (list.size() > MAX_REGIONS) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "at most " + MAX_REGIONS + " snapshot regions");
        }
        long volume = 0;
        for (JsonElement r : list) {
            if (!r.isJsonObject()) {
                throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "each snapshot region must be an object");
            }
            JsonObject o = r.getAsJsonObject();
            int dim = (int) optLong(o, "dim", Integer.MIN_VALUE);
            Region region = new Region(dim, xyz(o, "from"), xyz(o, "to"));
            volume += region.volume();
            out.add(region);
        }
        if (volume > Config.undoMaxRegionBlocks) {
            throw new ProtocolException(
                Protocol.ERR_BAD_REQUEST,
                "snapshot regions cover " + volume
                    + " blocks; at most "
                    + Config.undoMaxRegionBlocks
                    + " (undo.undoMaxRegionBlocks)");
        }
        return out;
    }

    private static int[] xyz(JsonObject o, String key) throws ProtocolException {
        JsonElement el = o.get(key);
        try {
            JsonArray a = el.getAsJsonArray();
            if (a.size() == 3) {
                return new int[] { (int) Math.floor(
                    a.get(0)
                        .getAsDouble()),
                    (int) Math.floor(
                        a.get(1)
                            .getAsDouble()),
                    (int) Math.floor(
                        a.get(2)
                            .getAsDouble()) };
            }
        } catch (RuntimeException ignored) {
            // reported below
        }
        throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "snapshot region needs \"" + key + "\": [x,y,z]");
    }

    private static long clampTimeout(long requested) {
        return Math.max(10, Math.min(Config.evalMaxTimeoutMillis, requested));
    }

    /**
     * Runs a compiled script now (server thread) and builds its {@code eval_result}.
     *
     * @param journal the change journal of a confirmed write (bound as {@code undo}), or null
     */
    static JsonObject runNow(String id, String mode, ScriptEngine.Compiled compiled, Object params, long timeoutMs,
        String playerName, boolean confirmed, ChangeJournal journal) {
        EntityPlayerMP player = playerName == null ? null : findPlayer(playerName);
        Map<String, Object> bindings = bindings(player, playerName, params);
        bindings.put("undo", new ScriptUndo(journal));
        JsonDump.Limits limits = new JsonDump.Limits(8, 500, 8000, Config.evalMaxResultChars);
        JsonObject outcome = ScriptEngine.shared()
            .run(compiled, bindings, timeoutMs, limits, Config.evalMaxOutputChars);
        JsonObject r = result(id, mode);
        if (confirmed) {
            r.addProperty("confirmed", true);
        }
        for (Map.Entry<String, JsonElement> e : outcome.entrySet()) {
            r.add(e.getKey(), e.getValue());
        }
        if (playerName != null && player == null) {
            addWarning(r, "player " + playerName + " is not online: `player` is null");
        }
        return r;
    }

    /**
     * A confirmed write: snapshots the requested regions, runs the script inside a change journal and saves what it
     * changed as a snapshot (named in the result as {@code snapshot}). Server thread.
     */
    static Ran runConfirmed(WriteJob job, String asker) {
        ChangeJournal journal = UndoService.ready()
            ? ChangeJournal.begin(Config.undoMaxBlocksPerSnapshot, Config.undoMaxRegionBlocks)
            : null;
        JsonObject r;
        ChangeJournal.Result changes = null;
        List<String> extra = new ArrayList<>();
        try {
            if (journal != null) {
                EntityPlayerMP player = findPlayer(asker);
                for (Region region : job.regions) {
                    int dim = region.dim != Integer.MIN_VALUE ? region.dim : player != null ? player.dimension : 0;
                    try {
                        journal.saveRegion(
                            MinecraftServer.getServer()
                                .worldServerForDimension(dim),
                            region.from[0],
                            region.from[1],
                            region.from[2],
                            region.to[0],
                            region.to[1],
                            region.to[2]);
                    } catch (RuntimeException e) {
                        extra.add("snapshot region not saved: " + e.getMessage());
                    }
                }
            }
            r = runNow(job.id, "write", job.compiled, job.params, job.timeoutMs, asker, true, journal);
        } finally {
            if (journal != null) {
                changes = journal.end();
            }
        }
        SnapshotMeta snap = null;
        if (changes != null) {
            List<String> unseen = new ArrayList<>();
            for (String m : job.compiled.mutators) {
                if (!JOURNALED.contains(m)) {
                    unseen.add(m);
                }
            }
            if (!unseen.isEmpty() && job.regions.isEmpty() && journal.explicitSaves() == 0) {
                extra.add(
                    "the script also calls " + String.join(", ", unseen)
                        + ": undo restores only blocks it set and positions saved first (undo.save, undo.region, "
                        + "dave.te, or the request's snapshot region)");
            }
            snap = UndoService.record(changes, asker, job.summary, job.script, job.id, extra);
            List<String> warnings = new ArrayList<>(changes.warnings);
            warnings.addAll(extra);
            for (String w : warnings) {
                addWarning(r, "undo: " + w);
            }
        } else if (Config.undoEnabled) {
            addWarning(r, "undo: no snapshot was recorded (the snapshot store is not open)");
        }
        if (snap != null) {
            r.addProperty("snapshot", snap.id);
            r.addProperty("snapshotBlocks", snap.blocks);
            r.addProperty("snapshotEntities", snap.entities);
            if (snap.partial) {
                r.addProperty("snapshotPartial", true);
            }
        }
        return new Ran(r, snap);
    }

    /** A confirmed run: its {@code eval_result} and the snapshot it recorded (or null). */
    static final class Ran {

        final JsonObject result;
        final SnapshotMeta snapshot;

        Ran(JsonObject result, SnapshotMeta snapshot) {
            this.result = result;
            this.snapshot = snapshot;
        }
    }

    public static Map<String, Object> bindings(EntityPlayerMP player, String playerName, Object params) {
        Map<String, Object> b = new HashMap<>();
        MinecraftServer server = MinecraftServer.getServer();
        World world = player != null ? player.worldObj : DimensionManager.getWorld(0);
        b.put("server", server);
        b.put("player", player);
        b.put("asker", playerName);
        b.put("world", world);
        b.put("params", params == null ? new HashMap<String, Object>() : params);
        b.put("dave", new ServerEvalHelpers());
        b.put("log", DaveBridge.LOG);
        b.put("undo", new ScriptUndo(null));
        return b;
    }

    private static JsonObject result(String id, String mode) {
        JsonObject r = Protocol.message("eval_result");
        if (id != null) {
            r.addProperty("id", id);
        }
        r.addProperty("mode", mode);
        r.addProperty("side", "server");
        return r;
    }

    private static JsonObject compileFailure(String id, String mode, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause()
            : failure;
        JsonObject r = result(id, mode);
        r.addProperty("ok", false);
        if (cause instanceof ScriptEngine.CompileError) {
            r.addProperty("errorType", ((ScriptEngine.CompileError) cause).kind);
            r.addProperty("error", cause.getMessage());
        } else {
            r.addProperty("errorType", "compile");
            r.addProperty("error", String.valueOf(cause));
        }
        return r;
    }

    private static void addWarning(JsonObject r, String warning) {
        JsonArray w = r.has("warnings") ? r.getAsJsonArray("warnings") : new JsonArray();
        w.add(new JsonPrimitive(warning));
        r.add("warnings", w);
    }

    // ------------------------------------------------------------------ confirm (write mode)

    /**
     * Shows the asking player what will change, with Confirm and Cancel buttons, and parks the script. The reply to
     * the agent comes later: when the player clicks, or when the confirm expires. Server thread.
     *
     * @return {@link #PENDING} (the reply is sent later), or an immediate error result
     */
    private static JsonObject askToConfirm(WriteJob job, String playerName, long confirmSeconds) {
        EntityPlayerMP player = findPlayer(playerName);
        // An asker who is not online (left, or a /dave ask test name) can still be confirmed from the console.
        String name = player != null ? player.getCommandSenderName() : playerName;
        java.util.UUID uuid = player != null ? player.getUniqueID() : TestHooks.uuidFor(playerName);
        PendingConfirms.Pending<Confirms.Job> pending = Confirms.create(player, name, uuid, confirmSeconds, job);
        if (pending == null) {
            JsonObject r = result(job.id, "write");
            r.addProperty("ok", false);
            r.addProperty("errorType", "too_many_pending");
            r.addProperty("error", name + " already has " + MAX_CONFIRMS_PER_PLAYER + " changes waiting for a confirm");
            return r;
        }
        JsonObject event = Protocol.message("eval_pending");
        if (job.id != null) {
            event.addProperty("id", job.id);
        }
        event.addProperty("player", name);
        event.addProperty("playerOnline", player != null);
        event.addProperty("confirmId", pending.token);
        event.addProperty("summary", job.summary);
        event.addProperty("expiresInSeconds", confirmSeconds);
        job.request.session()
            .send(event); // no reqId: the request's reply is the eval_result after the click
        DaveBridge.LOG.info(
            "Eval {} waits for {} to confirm{}: {}",
            pending.token,
            name,
            player == null ? " (not online: use /dave confirm from the console)" : "",
            job.summary);
        return PENDING;
    }

    /** Marker: the request is answered later. */
    static final JsonObject PENDING = new JsonObject();

    /** Runs {@code task} later on the eval timer thread (it must queue any game work itself). */
    static void schedule(Runnable task, long delay, TimeUnit unit) {
        TIMER.schedule(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                DaveBridge.LOG.warn("eval timer task failed", t);
            }
        }, delay, unit);
    }

    /** {@code /dave confirm|cancel <token>}. See {@link Confirms#onCommand}. */
    public static IChatComponent onConfirmCommand(net.minecraft.command.ICommandSender sender, String token,
        boolean confirm) {
        return Confirms.onCommand(sender, token, confirm);
    }

    /** {@code /dave pending}: every change waiting for a confirm, one line each. */
    public static List<String> describePending() {
        return Confirms.describe();
    }

    /** Runs {@code backupCommand} as the server, if configured. Best effort; a failure is logged, not fatal. */
    private static void runBackupCommand() {
        String cmd = Config.backupCommand == null ? "" : Config.backupCommand.trim();
        if (cmd.isEmpty()) {
            return;
        }
        try {
            MinecraftServer server = MinecraftServer.getServer();
            server.getCommandManager()
                .executeCommand(server, cmd.startsWith("/") ? cmd.substring(1) : cmd);
            DaveBridge.LOG.info("Ran backup command before a confirmed change: {}", cmd);
        } catch (Throwable t) {
            DaveBridge.LOG.warn("Backup command '{}' failed", cmd, t);
        }
    }

    /** Server stopping: answers every waiting confirm. */
    public static void serverStopping() {
        ClientEvalRelay.serverStopping();
        Confirms.serverStopping();
    }

    // ------------------------------------------------------------------ describe

    private static void handleDescribe(MessageRouter router, Request request) throws ProtocolException {
        String target = request.optString("target", "looking")
            .toLowerCase(Locale.ROOT);
        String playerName = request.optString("player", null);
        JsonObject body = request.body();
        router.runOnMainThread(request, "describe", () -> {
            EntityPlayerMP player = playerName == null ? null : findPlayer(playerName);
            Object described;
            switch (target) {
                case "looking":
                    described = Describe.looking(requirePlayer(player, playerName), optLong(body, "reach", 64));
                    break;
                case "held":
                    described = Describe.item(requirePlayer(player, playerName).getHeldItem());
                    if (described == null) {
                        described = "empty hand";
                    }
                    break;
                case "player":
                    described = Describe.entity(requirePlayer(player, playerName));
                    break;
                case "block": {
                    int dim = (int) optLong(body, "dim", player != null ? player.dimension : 0);
                    World world = DimensionManager.getWorld(dim);
                    if (world == null) {
                        throw new ProtocolException(Protocol.ERR_NOT_FOUND, "dimension " + dim + " is not loaded");
                    }
                    described = Describe.block(
                        world,
                        (int) requireLong(body, "x"),
                        (int) requireLong(body, "y"),
                        (int) requireLong(body, "z"));
                    break;
                }
                case "entity": {
                    int dim = (int) optLong(body, "dim", player != null ? player.dimension : 0);
                    World world = DimensionManager.getWorld(dim);
                    net.minecraft.entity.Entity e = world == null ? null
                        : world.getEntityByID((int) requireLong(body, "entityId"));
                    if (e == null) {
                        throw new ProtocolException(Protocol.ERR_NOT_FOUND, "no such entity in dimension " + dim);
                    }
                    described = Describe.entity(e);
                    break;
                }
                default:
                    throw new ProtocolException(
                        Protocol.ERR_BAD_REQUEST,
                        "target must be looking, held, player, block or entity");
            }
            JsonObject reply = Protocol.message("description");
            reply.addProperty("target", target);
            reply.add("description", JsonDump.dump(described, new JsonDump.Limits(8, 200, 4000, 64 * 1024)));
            return reply;
        });
    }

    private static EntityPlayerMP requirePlayer(EntityPlayerMP player, String name) throws ProtocolException {
        if (player == null) {
            throw new ProtocolException(
                name == null ? Protocol.ERR_BAD_REQUEST : Protocol.ERR_NOT_FOUND,
                name == null ? "this target needs \"player\"" : "player " + name + " is not online");
        }
        return player;
    }

    // ------------------------------------------------------------------ helpers

    static long now() {
        return System.currentTimeMillis();
    }

    /** An online player by name, case-insensitive, or null. Server thread. */
    public static EntityPlayerMP findPlayer(String name) {
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

    /**
     * Like {@link MessageRouter#runOnMainThread}, but a task may return {@link #PENDING} to answer later itself.
     * Failures become {@code error} replies with the request's reqId.
     */
    public static void onMain(Request request, String label, java.util.concurrent.Callable<JsonObject> work) {
        DaveServer.QUEUE.submit(label, work)
            .whenComplete((result, failure) -> {
                try {
                    if (failure == null) {
                        if (result == PENDING) {
                            return;
                        }
                        if (result != null) {
                            request.reply(result);
                        } else {
                            request.ack();
                        }
                        return;
                    }
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                    if (cause instanceof ProtocolException) {
                        request.error(((ProtocolException) cause).code(), cause.getMessage());
                    } else if (cause instanceof java.util.concurrent.RejectedExecutionException) {
                        request.error(Protocol.ERR_QUEUE_FULL, cause.getMessage());
                    } else if (cause instanceof java.util.concurrent.CancellationException) {
                        request.error(Protocol.ERR_CANCELLED, String.valueOf(cause.getMessage()));
                    } else {
                        DaveBridge.LOG.warn("Main-thread task '{}' failed", label, cause);
                        request.error(Protocol.ERR_INTERNAL, cause.toString());
                    }
                } catch (Throwable t) {
                    DaveBridge.LOG.warn("Failed to report result of '{}'", label, t);
                }
            });
    }

    public static long optLong(JsonObject body, String key, long def) throws ProtocolException {
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

    static long requireLong(JsonObject body, String key) throws ProtocolException {
        if (!body.has(key) || body.get(key)
            .isJsonNull()) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "missing '" + key + "'");
        }
        return optLong(body, key, 0);
    }

    /** JSON params as plain Java maps, lists, strings, doubles and booleans. */
    public static Object toJava(JsonElement el) {
        return el == null || el.isJsonNull() ? null : GSON.fromJson(el, Object.class);
    }

    public static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** For status: how many confirms are waiting. */
    public static int pendingConfirms() {
        return Confirms.size();
    }
}
