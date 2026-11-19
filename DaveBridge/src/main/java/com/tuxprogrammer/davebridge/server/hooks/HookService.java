package com.tuxprogrammer.davebridge.server.hooks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tuxprogrammer.davebridge.DaveBridge;
import com.tuxprogrammer.davebridge.common.Config;
import com.tuxprogrammer.davebridge.common.eval.JsonDump;
import com.tuxprogrammer.davebridge.common.eval.ScriptEngine;
import com.tuxprogrammer.davebridge.server.DaveServer;
import com.tuxprogrammer.davebridge.server.bridge.ClientSession;
import com.tuxprogrammer.davebridge.server.bridge.MessageRouter;
import com.tuxprogrammer.davebridge.server.bridge.Protocol;
import com.tuxprogrammer.davebridge.server.bridge.ProtocolException;
import com.tuxprogrammer.davebridge.server.bridge.Request;
import com.tuxprogrammer.davebridge.server.command.TestHooks;
import com.tuxprogrammer.davebridge.server.compat.BqQuests;
import com.tuxprogrammer.davebridge.server.eval.EvalService;

/**
 * Event hooks (PROTOCOL.md 1.9): the agent subscribes to game events or to a polled Groovy condition and gets a
 * {@code hook_fired} event when one fires, which the daemon turns into a turn for Dave.
 * <p>
 * Event hooks are fed by {@link HookEvents} (Forge and FML events); a poll hook's condition runs every
 * {@code everyTicks} ticks on the server thread, within {@code hooks.hookTickBudgetMillis} per tick, and fires when it
 * turns true. Scripts are compiled once, refused if they change the world, and run with eval's time limit and
 * exception capture; one that keeps failing is disabled ({@code hook_disabled}). Hooks live in the world folder
 * ({@code <world>/davebridge/hooks.json}), so they survive restarts; notifications that find no agent connected wait
 * in an outbox (saved with them) until one connects.
 * <p>
 * All hook state is touched on the server thread only, except the outbox and {@link HookRegistry#watches}.
 */
public final class HookService {

    static final HookRegistry REG = new HookRegistry();
    private static final Map<String, ScriptEngine.Compiled> COMPILED = new HashMap<>();
    private static final Map<String, Map<String, Object>> STATE = new HashMap<>();
    private static final Map<String, Long> NEXT_RUN = new HashMap<>();
    private static File file;
    private static boolean dirty;
    private static long tick;
    private static long lastSaveTick;

    private HookService() {}

    public static void register(MessageRouter router) {
        router.register("hook_add", HookService::handleAdd);
        router.register("hook_list", request -> handleList(router, request));
        router.register("hook_remove", request -> handleRemove(router, request));
        HookEvents.register();
        DaveServer.BRIDGE.addAuthListener(HookService::flushOutbox);
    }

    // ------------------------------------------------------------------ lifecycle

    /** After the worlds loaded: reads {@code hooks.json} and compiles the hook scripts. Server thread. */
    public static void serverStarted() {
        COMPILED.clear();
        STATE.clear();
        NEXT_RUN.clear();
        try {
            File root = DimensionManager.getCurrentSaveRootDirectory();
            if (root == null) {
                DaveBridge.LOG.warn("Hooks: no world folder; hooks are not saved");
                file = null;
                REG.load("{}");
                return;
            }
            file = new File(new File(root, "davebridge"), "hooks.json");
            if (file.isFile()) {
                REG.load(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                DaveBridge.LOG
                    .info("Hooks: {} hook(s), {} waiting notification(s) from {}", REG.size(), REG.outboxSize(), file);
            } else {
                REG.load("{}");
            }
        } catch (IOException | RuntimeException e) {
            if (file != null) {
                File aside = new File(file.getParentFile(), "hooks.json.unreadable-" + System.currentTimeMillis());
                DaveBridge.LOG
                    .error("Hooks: could not read {} ({}); moved it to {}", file, e.toString(), aside.getName());
                file.renameTo(aside);
            }
            REG.load("{}");
        }
        for (HookSpec h : REG.all()) {
            if (h.script != null) {
                compileFor(h);
            }
        }
    }

    private static void compileFor(HookSpec h) {
        String id = h.id;
        ScriptEngine.shared()
            .compileAsync(h.script)
            .whenComplete((compiled, failure) -> DaveServer.QUEUE.submit("hook_compiled", () -> {
                HookSpec cur = REG.get(id);
                if (cur == null) {
                    return;
                }
                if (failure != null || !compiled.mutators.isEmpty()) {
                    String why = failure != null ? "script no longer compiles: " + cause(failure)
                        : "script changes the world (" + String.join(", ", compiled.mutators) + ")";
                    disable(cur, why);
                    return;
                }
                COMPILED.put(id, compiled);
            }));
    }

    /** Saves the hooks and forgets the scripts. Server thread. */
    public static void serverStopping() {
        save();
        COMPILED.clear();
        STATE.clear();
        NEXT_RUN.clear();
    }

    /** Writes {@code hooks.json} now (atomic). */
    static void save() {
        File f = file;
        dirty = false;
        lastSaveTick = tick;
        if (f == null) {
            return;
        }
        try {
            File parent = f.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            File tmp = new File(parent, f.getName() + ".tmp");
            Files.write(
                tmp.toPath(),
                REG.toJson()
                    .getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                    tmp.toPath(),
                    f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            DaveBridge.LOG.error("Hooks: could not save {}", f, t);
        }
    }

    static void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ tick (server thread)

    public static void tick(long serverTick) {
        tick = serverTick;
        long now = System.currentTimeMillis();
        if (serverTick % 20 == 0) {
            for (HookSpec h : REG.expire(now)) {
                forget(h.id);
                markDirty();
                DaveBridge.LOG.info("Hooks: {} ({}) expired", h.id, h.name);
            }
        }
        if (Config.hooksEnabled) {
            runPolls(serverTick);
        }
        if (dirty && serverTick - lastSaveTick >= 100) {
            save();
        }
    }

    private static void runPolls(long serverTick) {
        List<HookSpec> polls = REG.of(HookSpec.POLL);
        if (polls.isEmpty()) {
            return;
        }
        // Most overdue first, so a slow condition cannot starve the others.
        polls.sort((a, b) -> Long.compare(next(a), next(b)));
        long budget = Math.max(1, Config.hookTickBudgetMillis) * 1_000_000L;
        long start = System.nanoTime();
        int ran = 0;
        for (HookSpec h : polls) {
            if (next(h) > serverTick) {
                break;
            }
            ScriptEngine.Compiled c = COMPILED.get(h.id);
            if (c == null) {
                continue; // still compiling
            }
            if (ran > 0 && System.nanoTime() - start >= budget) {
                break; // the rest run next tick
            }
            NEXT_RUN.put(h.id, serverTick + h.everyTicks);
            ran++;
            poll(h, c, false);
        }
    }

    private static long next(HookSpec h) {
        Long n = NEXT_RUN.get(h.id);
        return n == null ? 0 : n;
    }

    /**
     * Runs a poll hook's condition once and fires on its rising edge (or on every true run without {@code edge}).
     *
     * @return the outcome, for hook_add's first run
     */
    private static ScriptEngine.Outcome poll(HookSpec h, ScriptEngine.Compiled c, boolean first) {
        ScriptEngine.Outcome o = run(h, c, null, null);
        if (!o.ok()) {
            failed(h, o);
            return o;
        }
        if (h.errors != 0) {
            h.errors = 0;
            markDirty();
        }
        boolean value = o.truthy();
        Boolean prev = h.lastValue;
        if (value) {
            boolean shouldFire = !h.edge || (prev == null ? h.fireIfTrueAtStart : !prev);
            if (shouldFire && !first) {
                // A firing held back by a cooldown or the rate limit leaves lastValue false, so it is retried.
                if (fire(h, facts(h), o, false)) {
                    h.lastValue = true;
                }
            } else if (!shouldFire) {
                h.lastValue = true;
            }
        } else {
            h.lastValue = false;
        }
        if (prev != h.lastValue) {
            markDirty();
        }
        return o;
    }

    private static Map<String, Object> facts(HookSpec h) {
        return new LinkedHashMap<>();
    }

    /** Runs a hook script with eval's bindings plus {@code hook}, {@code state}, {@code event} and {@code facts}. */
    private static ScriptEngine.Outcome run(HookSpec h, ScriptEngine.Compiled c, Object event,
        Map<String, Object> facts) {
        EntityPlayerMP owner = EvalService.findPlayer(h.owner);
        Map<String, Object> b = EvalService.bindings(owner, h.owner, EvalService.toJava(h.params));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", h.id);
        info.put("name", h.name);
        info.put("owner", h.owner);
        info.put("fireCount", h.fireCount);
        info.put("lastFiredAt", h.lastFiredAt);
        b.put("hook", info);
        b.put("state", STATE.computeIfAbsent(h.id, k -> new HashMap<>()));
        b.put("event", event);
        b.put("facts", facts == null ? new LinkedHashMap<String, Object>() : facts);
        long timeout = Math.max(1, Math.min(Config.hookPollTimeoutMillis, Config.evalMaxTimeoutMillis));
        return ScriptEngine.shared()
            .evaluate(c, b, timeout, 2000);
    }

    private static void failed(HookSpec h, ScriptEngine.Outcome o) {
        h.errors++;
        h.lastError = o.errorType() + ": " + o.error();
        markDirty();
        if (h.errors >= Config.hookMaxErrors) {
            disable(h, "its script failed " + h.errors + " times in a row; last: " + h.lastError);
        }
    }

    /** Disables a hook and tells the agent ({@code hook_disabled}). */
    static void disable(HookSpec h, String why) {
        h.disabled = true;
        h.lastError = why;
        REG.refreshActive();
        forget(h.id);
        markDirty();
        DaveBridge.LOG.warn("Hooks: disabled {} ({}): {}", h.id, h.name, why);
        JsonObject ev = Protocol.message("hook_disabled");
        describeInto(h, ev);
        ev.addProperty("reason", "errors");
        ev.addProperty("error", why);
        deliver(ev);
    }

    private static void forget(String id) {
        COMPILED.remove(id);
        STATE.remove(id);
        NEXT_RUN.remove(id);
    }

    // ------------------------------------------------------------------ events

    /**
     * An event happened (any thread). Cheap when no hook watches it; otherwise matched and fired on the server thread.
     */
    static void onEvent(String type, Map<String, Object> facts, Object event) {
        if (!Config.hooksEnabled || !REG.watches(type)) {
            return;
        }
        if (DaveServer.QUEUE.isMainThread()) {
            // On the server thread the hooks can be read safely: skip the queue when no filter matches (a mob farm
            // with an entity_death hook for a boss must not queue a task per dead zombie).
            boolean any = false;
            for (HookSpec h : REG.of(type)) {
                if (h.matches(facts)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return;
            }
        }
        DaveServer.QUEUE.submit("hook_event", () -> dispatch(type, facts, event));
    }

    private static void dispatch(String type, Map<String, Object> facts, Object event) {
        for (HookSpec h : REG.of(type)) {
            try {
                if (!h.matches(facts)) {
                    continue;
                }
                ScriptEngine.Outcome o = null;
                if (h.script != null) {
                    ScriptEngine.Compiled c = COMPILED.get(h.id);
                    if (c == null) {
                        continue; // still compiling
                    }
                    o = run(h, c, event, facts);
                    if (!o.ok()) {
                        failed(h, o);
                        continue;
                    }
                    if (h.errors != 0) {
                        h.errors = 0;
                        markDirty();
                    }
                    if (!o.truthy()) {
                        continue;
                    }
                }
                fire(h, facts, o, false);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("Hooks: {} failed on {}", h.id, type, t);
            }
        }
    }

    /**
     * Sends {@code hook_fired} for {@code h} if its cooldown and the rate limit allow; removes a one-shot hook.
     * Server thread.
     *
     * @return true if it fired
     */
    static boolean fire(HookSpec h, Map<String, Object> facts, ScriptEngine.Outcome outcome, boolean test) {
        long now = System.currentTimeMillis();
        if (!test && !REG.allowFire(h, now, Config.hookMaxFiresPerMinute)) {
            markDirty();
            return false;
        }
        h.fireCount++;
        h.lastFiredAt = now;
        JsonObject ev = Protocol.message("hook_fired");
        describeInto(h, ev);
        ev.addProperty("fireCount", h.fireCount);
        ev.addProperty("suppressed", h.suppressed);
        h.suppressed = 0;
        JsonObject detail = new JsonObject();
        JsonDump.Limits limits = new JsonDump.Limits(6, 50, 1000, 8000);
        for (Map.Entry<String, Object> e : (facts == null ? new LinkedHashMap<String, Object>() : facts).entrySet()) {
            detail.add(e.getKey(), ScriptEngine.toJson(e.getValue(), new JsonDump(limits), 200));
        }
        if (outcome != null && outcome.value != null && !(outcome.value instanceof Boolean)) {
            detail.add("result", ScriptEngine.toJson(outcome.value, new JsonDump(limits), 200));
        }
        if (outcome != null && !outcome.output.isEmpty()) {
            detail.addProperty("output", outcome.output);
        }
        if (test) {
            detail.addProperty("test", true);
        }
        ev.add("detail", detail);
        boolean removed = h.once && !test;
        ev.addProperty("removed", removed);
        if (removed) {
            REG.remove(h.id);
            forget(h.id);
        }
        markDirty();
        deliver(ev);
        DaveBridge.LOG.info("Hooks: {} ({}) fired for {}{}", h.id, h.name, h.owner, removed ? "; removed (once)" : "");
        return true;
    }

    private static void describeInto(HookSpec h, JsonObject ev) {
        ev.addProperty("hookId", h.id);
        ev.addProperty("name", h.name);
        ev.addProperty("event", h.event);
        ev.addProperty("owner", h.owner);
        if (h.ownerUuid != null) {
            ev.addProperty("ownerUuid", h.ownerUuid);
        }
        ev.addProperty("ownerOnline", EvalService.findPlayer(h.owner) != null);
        ev.addProperty("notify", h.notify);
        if (h.note != null) {
            ev.addProperty("note", h.note);
        }
        ev.addProperty("once", h.once);
    }

    /** Broadcasts to the agents, or keeps it in the outbox when none is connected. */
    private static void deliver(JsonObject ev) {
        if (DaveServer.BRIDGE.broadcast(ev) == 0) {
            ev.addProperty("queued", true);
            REG.queue(ev, Config.hookOutboxSize);
            markDirty();
        }
    }

    /** An agent authenticated (socket thread): hand it what fired while none was connected. */
    static void flushOutbox(ClientSession session) {
        List<String> waiting = REG.drainOutbox();
        for (String m : waiting) {
            try {
                session.sendRaw(m);
            } catch (Throwable t) {
                DaveBridge.LOG.debug("Hooks: outbox delivery failed", t);
            }
        }
        if (!waiting.isEmpty()) {
            DaveBridge.LOG.info("Hooks: delivered {} waiting notification(s) to {}", waiting.size(), session);
            DaveServer.QUEUE.submit("hook_outbox_saved", HookService::markDirty);
        }
    }

    // ------------------------------------------------------------------ agent requests

    private static HookSpec.Limits limits() {
        return new HookSpec.Limits(
            Config.hookMinPollTicks,
            Config.hookDefaultCooldownSeconds,
            Config.hookDefaultTtlHours * 3_600_000L,
            Config.hookMaxTtlHours * 3_600_000L);
    }

    private static void handleAdd(Request request) throws ProtocolException {
        if (!Config.hooksEnabled) {
            throw new ProtocolException("disabled", "hooks are disabled on this server (hooks.hooksEnabled)");
        }
        JsonElement el = request.body()
            .get("hook");
        if (el == null || !el.isJsonObject()) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "missing \"hook\" object");
        }
        HookSpec h;
        try {
            h = HookSpec.parse(el.getAsJsonObject(), limits(), System.currentTimeMillis());
        } catch (HookSpec.Invalid e) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, e.getMessage());
        }
        if (h.script == null) {
            EvalService.onMain(request, "hook_add", () -> add(request, h, null));
            return;
        }
        ScriptEngine.shared()
            .compileAsync(h.script)
            .whenComplete((compiled, failure) -> {
                if (failure != null) {
                    request.error(Protocol.ERR_BAD_REQUEST, "the hook script did not compile: " + cause(failure));
                    return;
                }
                if (!compiled.mutators.isEmpty()) {
                    request.error(
                        Protocol.ERR_BAD_REQUEST,
                        "hook scripts run unattended and must not change the world (they call "
                            + String.join(", ", compiled.mutators)
                            + "); only watch in the hook and make changes with a confirmed write");
                    return;
                }
                EvalService.onMain(request, "hook_add", () -> add(request, h, compiled));
            });
    }

    /** Server thread: resolves the owner and quest, adds the hook, runs a poll condition once. */
    private static JsonObject add(Request request, HookSpec h, ScriptEngine.Compiled compiled) throws Exception {
        EntityPlayerMP owner = EvalService.findPlayer(h.owner);
        if (owner != null) {
            h.owner = owner.getCommandSenderName();
        }
        UUID uuid = owner != null ? owner.getUniqueID() : TestHooks.uuidFor(h.owner);
        h.ownerUuid = uuid.toString();
        List<String> warnings = new ArrayList<>();
        if ("quest_complete".equals(h.event)) {
            if (!BqQuests.available()) {
                throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "BetterQuesting is not on this server");
            }
            if (h.filter.quest != null) {
                BqQuests.Quest q = BqQuests.find(h.filter.quest);
                if (q == null) {
                    warnings.add("no quest named " + h.filter.quest + "; matching quest names that contain it");
                } else {
                    h.filter.quest = q.id.toString();
                    if (h.name.equals(h.event)) {
                        h.name = "quest " + q.name.replaceAll("§.", "");
                    }
                }
            }
        }
        try {
            REG.add(h, Config.hookMaxCount, Config.hookMaxPerOwner);
        } catch (HookRegistry.Full e) {
            throw new ProtocolException("limit", e.getMessage());
        }
        JsonObject ack = Protocol.message("ack");
        ack.addProperty("ok", true);
        ack.addProperty("for", "hook_add");
        if (compiled != null) {
            COMPILED.put(h.id, compiled);
        }
        boolean fireNow = false;
        if (HookSpec.POLL.equals(h.event)) {
            ScriptEngine.Outcome o = poll(h, compiled, true);
            if (!o.ok()) {
                REG.remove(h.id);
                forget(h.id);
                throw new ProtocolException(
                    Protocol.ERR_BAD_REQUEST,
                    "the condition failed on its first run (" + o.errorType() + "): " + o.error());
            }
            h.errors = 0;
            JsonObject first = new JsonObject();
            first.addProperty("truthy", o.truthy());
            first.add("value", ScriptEngine.toJson(o.value, new JsonDump(new JsonDump.Limits(4, 20, 500, 4000)), 200));
            ack.add("firstRun", first);
            fireNow = o.truthy() && h.fireIfTrueAtStart;
            NEXT_RUN.put(h.id, tick + h.everyTicks);
        }
        ack.add("hook", h.toJson());
        if (!warnings.isEmpty()) {
            JsonArray w = new JsonArray();
            for (String s : warnings) {
                w.add(new JsonPrimitive(s));
            }
            ack.add("warnings", w);
        }
        markDirty();
        save();
        DaveBridge.LOG.info("Hooks: added {} ({}, {}) for {}", h.id, h.event, h.name, h.owner);
        if (fireNow) {
            String id = h.id;
            DaveServer.QUEUE.submit("hook_first_fire", () -> {
                HookSpec cur = REG.get(id);
                if (cur != null && fire(cur, facts(cur), null, false)) {
                    cur.lastValue = true;
                }
            });
        }
        return request.reqId() != null ? ack : null; // acks only for requests with a reqId
    }

    private static void handleList(MessageRouter router, Request request) throws ProtocolException {
        String owner = request.optString("owner", null);
        router.runOnMainThread(request, "hook_list", () -> {
            JsonObject reply = Protocol.message("hooks");
            JsonArray list = new JsonArray();
            for (HookSpec h : REG.forOwner(owner)) {
                list.add(h.toJson());
            }
            reply.add("hooks", list);
            reply.addProperty("enabled", Config.hooksEnabled);
            return reply;
        });
    }

    private static void handleRemove(MessageRouter router, Request request) throws ProtocolException {
        String id = request.optString("id", null);
        String owner = request.optString("owner", null);
        if (id == null && owner == null) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "hook_remove needs \"id\" or \"owner\"");
        }
        router.runOnMainThread(request, "hook_remove", () -> {
            List<HookSpec> removed = new ArrayList<>();
            if (id != null) {
                HookSpec h = REG.remove(id);
                if (h == null) {
                    throw new ProtocolException(Protocol.ERR_NOT_FOUND, "no hook " + id);
                }
                removed.add(h);
            } else {
                removed.addAll(REG.removeOwner(owner));
            }
            JsonArray ids = new JsonArray();
            for (HookSpec h : removed) {
                forget(h.id);
                ids.add(new JsonPrimitive(h.id));
            }
            markDirty();
            save();
            JsonObject ack = request.ackMessage();
            if (ack != null) {
                ack.add("removed", ids);
            }
            return ack;
        });
    }

    // ------------------------------------------------------------------ commands

    /** {@code /dave hooks [owner]}. */
    public static List<String> describe(String owner) {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<HookSpec> list = REG.forOwner(owner);
        out.add(
            list.size() + " hook(s)"
                + (owner == null ? "" : " of " + owner)
                + (Config.hooksEnabled ? "" : " (hooks are disabled in the config)")
                + (REG.outboxSize() > 0 ? ", " + REG.outboxSize() + " notification(s) waiting for an agent" : ""));
        for (HookSpec h : list) {
            out.add(h.describe(now));
        }
        return out;
    }

    /** {@code /dave hooks remove <id>}. */
    public static String removeCommand(String id) {
        HookSpec h = REG.remove(id);
        if (h == null) {
            return "No hook " + id + ".";
        }
        forget(h.id);
        markDirty();
        save();
        return "Removed " + h.id + " (" + h.name + ").";
    }

    /** {@code /dave hooks fire <id>}: sends a test notification (detail.test=true) without removing the hook. */
    public static String fireCommand(String id) {
        HookSpec h = REG.get(id);
        if (h == null) {
            return "No hook " + id + ".";
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("player", h.owner);
        fire(h, facts, null, true);
        return "Sent a test notification for " + h.id + " (" + h.name + ").";
    }

    public static int count() {
        return REG.size();
    }

    // ------------------------------------------------------------------ helpers

    private static String cause(Throwable failure) {
        Throwable c = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause()
            : failure;
        return c instanceof ScriptEngine.CompileError ? c.getMessage() : String.valueOf(c);
    }

    /** For tests and the outbox: parses a stored message. */
    static JsonObject parse(String raw) {
        return new JsonParser().parse(raw)
            .getAsJsonObject();
    }
}
