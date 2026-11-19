package com.tuxprogrammer.davebridge.common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.regex.Pattern;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import com.tuxprogrammer.davebridge.DaveBridge;

/**
 * Server-side config ({@code config/davebridge.cfg}). Loaded on both physical sides because an integrated
 * (singleplayer) server runs the same server code; client-only options live in {@code ClientConfig}.
 * <p>
 * Fields are volatile because socket and netty threads read them while {@code /dave reload} rewrites them from the
 * server thread.
 */
public final class Config {

    public static final String CATEGORY_NETWORK = "network";
    public static final String CATEGORY_CHAT = "chat";
    public static final String CATEGORY_QUEUE = "queue";

    // network
    public static volatile boolean enabled = true;
    public static volatile boolean startOnIntegratedServer = false;
    public static volatile String bindAddress = "127.0.0.1";
    public static volatile int port = 25580;
    public static volatile String authToken = "";
    public static volatile int authTimeoutSeconds = 10;
    public static volatile int maxClients = 4;
    public static volatile int maxMessageBytes = 1 << 20;

    // chat
    public static volatile String[] triggerPrefixes = { "dave", "@dave", "!dave" };
    public static volatile boolean forwardAllChat = false;
    public static volatile String botName = "Dave";
    public static volatile String botNameColor = "aqua";
    public static volatile int chatWidth = 320;
    public static volatile int maxLinesPerMessage = 40;
    public static volatile boolean notifyWhenOffline = true;
    public static volatile int askCooldownMillis = 1000;
    public static volatile String[] urlAllowlist = new String[0];
    public static volatile String[] commandAllowlist = { "/dave", "/davebridge", "/bq_client", "/nei_bookmark",
        "/savewaypoint", "/guidenhc" };
    public static volatile String replySound = "random.orb";
    public static volatile float replySoundVolume = 0.5f;
    public static volatile float replySoundPitch = 1.4f;
    public static volatile int maxAskLength = 2000;

    // queue
    public static volatile int tickBudgetMillis = 5;
    public static volatile int maxQueueDepth = 1000;

    // eval (milestone 2)
    public static final String CATEGORY_EVAL = "eval";
    public static volatile boolean evalEnabled = true;
    public static volatile int evalTimeoutMillis = 500;
    public static volatile int evalMaxTimeoutMillis = 5000;
    public static volatile int evalMaxResultChars = 64 * 1024;
    public static volatile int evalMaxOutputChars = 16 * 1024;
    public static volatile int confirmTimeoutSeconds = 120;
    public static volatile int confirmMaxTimeoutSeconds = 600;
    public static volatile String backupCommand = "";
    public static volatile boolean clientEvalRelay = true;
    public static volatile int clientEvalTimeoutSeconds = 30;

    // hooks (event subscriptions, 0.3.0)
    public static final String CATEGORY_HOOKS = "hooks";
    public static volatile boolean hooksEnabled = true;
    public static volatile int hookMaxCount = 64;
    public static volatile int hookMaxPerOwner = 16;
    public static volatile int hookMinPollTicks = 20;
    public static volatile int hookPollTimeoutMillis = 50;
    public static volatile int hookTickBudgetMillis = 5;
    public static volatile int hookMaxFiresPerMinute = 30;
    public static volatile int hookDefaultCooldownSeconds = 10;
    public static volatile int hookMaxErrors = 5;
    public static volatile int hookDefaultTtlHours = 168;
    public static volatile int hookMaxTtlHours = 0;
    public static volatile int hookOutboxSize = 50;

    // undo (snapshots of confirmed world changes, 0.3.0)
    public static final String CATEGORY_UNDO = "undo";
    public static volatile boolean undoEnabled = true;
    public static volatile int undoMaxSnapshots = 100;
    public static volatile int undoMaxStorageMB = 64;
    public static volatile int undoMaxAgeDays = 30;
    public static volatile int undoMaxBlocksPerSnapshot = 100000;
    public static volatile int undoMaxRegionBlocks = 65536;
    public static volatile int undoTickBudgetMillis = 10;

    /** A non-empty {@code authToken} line as Forge writes it, e.g. {@code S:authToken=...}. */
    private static final Pattern TOKEN_LINE = Pattern.compile("(?m)^\\s*S:\"?authToken\"?\\s*=\\s*\\S");

    private static File configFile;
    /** True once the file has been read successfully at least once. */
    private static boolean loaded;

    private Config() {}

    public static void init(File file) {
        configFile = file;
        reload();
    }

    /**
     * Re-reads the config file. Writes back defaults and a generated token if they were missing.
     * <p>
     * If the file cannot be parsed, Forge renames it to {@code *.errored} and starts from an empty file, which would
     * silently replace the token and reset {@code bindAddress}. Instead this restores the file, keeps every current
     * value (at startup: keeps the bridge off) and returns false.
     *
     * @return true if the file was applied, false if it could not be parsed and the previous settings were kept
     */
    public static synchronized boolean reload() {
        File file = configFile;
        byte[] before = readIfPresent(file);
        Configuration cfg;
        try {
            cfg = new Configuration(file);
            cfg.load();
        } catch (RuntimeException e) {
            return parseFailed(file, before, e.toString());
        }
        if (before != null && before.length > 0 && file.length() == 0) {
            return parseFailed(file, before, "Forge could not parse it");
        }
        Property token = cfg.get(
            CATEGORY_NETWORK,
            "authToken",
            "",
            "Shared secret the agent must present. Generated randomly on first run if empty. Treat like a password.");
        boolean tokenMissing = token.getString() == null || token.getString()
            .trim()
            .isEmpty();
        if (tokenMissing && before != null
            && TOKEN_LINE.matcher(new String(before, StandardCharsets.UTF_8))
                .find()) {
            // The file has a token but it did not come through: never replace it with a generated one.
            return parseFailed(file, before, "its authToken could not be read");
        }

        cfg.setCategoryComment(
            CATEGORY_NETWORK,
            "WebSocket server the external agent connects to. Changes apply on /dave reload (the socket restarts).");
        enabled = cfg.getBoolean("enabled", CATEGORY_NETWORK, true, "Start the WebSocket bridge at all.");
        startOnIntegratedServer = cfg.getBoolean(
            "startOnIntegratedServer",
            CATEGORY_NETWORK,
            false,
            "Also open the socket when running a singleplayer/LAN world (normally only dedicated servers do).");
        bindAddress = cfg.getString(
            "bindAddress",
            CATEGORY_NETWORK,
            "127.0.0.1",
            "Address to bind. 127.0.0.1 for a local agent. In Docker, use 0.0.0.0 so the agent's container can reach "
                + "it over the stack network, and do not publish this port to the host or LAN.");
        port = cfg.getInt("port", CATEGORY_NETWORK, 25580, 1, 65535, "TCP port of the WebSocket server.");

        if (tokenMissing) {
            token.set(generateToken());
            DaveBridge.LOG.info("Generated a new authToken in {}", configFile);
        }
        authToken = token.getString()
            .trim();

        authTimeoutSeconds = cfg.getInt(
            "authTimeoutSeconds",
            CATEGORY_NETWORK,
            10,
            1,
            300,
            "Connections that have not authenticated within this many seconds are dropped.");
        maxClients = cfg
            .getInt("maxClients", CATEGORY_NETWORK, 4, 1, 64, "Maximum simultaneous WebSocket connections.");
        maxMessageBytes = cfg.getInt(
            "maxMessageBytes",
            CATEGORY_NETWORK,
            1 << 20,
            1024,
            64 << 20,
            "Largest inbound WebSocket frame accepted, in bytes.");

        cfg.setCategoryComment(CATEGORY_CHAT, "Chat forwarding and how Dave's replies look in game.");
        triggerPrefixes = cfg.getStringList(
            "triggerPrefixes",
            CATEGORY_CHAT,
            new String[] { "dave", "@dave", "!dave" },
            "Chat messages starting with one of these (case-insensitive) are forwarded with triggered=true.");
        forwardAllChat = cfg.getBoolean(
            "forwardAllChat",
            CATEGORY_CHAT,
            false,
            "Forward every chat message (non-matching ones with triggered=false), not just triggered ones.");
        botName = cfg.getString("botName", CATEGORY_CHAT, "Dave", "Name shown in <...> before Dave's chat lines.");
        botNameColor = cfg.getString(
            "botNameColor",
            CATEGORY_CHAT,
            "aqua",
            "Color of the bot name: a Minecraft color name such as aqua, gold, light_purple.");
        chatWidth = cfg.getInt(
            "chatWidth",
            CATEGORY_CHAT,
            320,
            100,
            1000,
            "Width of the players' chat box in pixels (320 is vanilla's default at 100% chat width). Dave's replies "
                + "are cut at spaces into lines this wide, so the client never wraps them and never splits a word.");
        maxLinesPerMessage = cfg.getInt(
            "maxLinesPerMessage",
            CATEGORY_CHAT,
            40,
            1,
            500,
            "A single say message is cut off after this many chat lines.");

        notifyWhenOffline = cfg.getBoolean(
            "notifyWhenOffline",
            CATEGORY_CHAT,
            true,
            "Tell a player privately when they address Dave but no agent is connected.");
        askCooldownMillis = cfg.getInt(
            "askCooldownMillis",
            CATEGORY_CHAT,
            1000,
            0,
            60000,
            "Minimum time between Ask packets from one player's client mod. Extra asks are dropped.");
        urlAllowlist = cfg.getStringList(
            "urlAllowlist",
            CATEGORY_CHAT,
            new String[0],
            "Hosts Dave may link to with {url:}, matched as domain suffixes (example.org also allows wiki.example.org). "
                + "Empty: any http/https host.");
        commandAllowlist = cfg.getStringList(
            "commandAllowlist",
            CATEGORY_CHAT,
            new String[] { "/dave", "/davebridge", "/bq_client", "/nei_bookmark", "/savewaypoint", "/guidenhc" },
            "Commands a {cmd:} button (or a run_command in raw components) may run when a player clicks it.");
        replySound = cfg.getString(
            "replySound",
            CATEGORY_CHAT,
            "random.orb",
            "Sound played to each recipient when Dave replies (only they hear it). Empty to disable.");
        replySoundVolume = (float) cfg
            .get(CATEGORY_CHAT, "replySoundVolume", 0.5, "Volume of the reply sound (0-1).", 0.0, 1.0)
            .getDouble();
        replySoundPitch = (float) cfg
            .get(CATEGORY_CHAT, "replySoundPitch", 1.4, "Pitch of the reply sound (0.5-2).", 0.5, 2.0)
            .getDouble();
        maxAskLength = cfg
            .getInt("maxAskLength", CATEGORY_CHAT, 2000, 16, 32000, "Longest question accepted from the client mod.");

        cfg.setCategoryComment(CATEGORY_QUEUE, "Main-thread task queue that runs agent actions during server ticks.");
        tickBudgetMillis = cfg.getInt(
            "tickBudgetMillis",
            CATEGORY_QUEUE,
            5,
            1,
            45,
            "Time per server tick spent running queued tasks. At least one task runs per tick regardless.");
        maxQueueDepth = cfg
            .getInt("maxQueueDepth", CATEGORY_QUEUE, 1000, 10, 100000, "Further tasks are rejected while this full.");

        loadEval(cfg);
        loadHooks(cfg);
        loadUndo(cfg);

        if (cfg.hasChanged()) {
            cfg.save();
        }
        loaded = true;
        return true;
    }

    private static void loadEval(Configuration cfg) {
        cfg.setCategoryComment(
            CATEGORY_EVAL,
            "Live eval: Groovy scripts from the agent, run on the server thread (PROTOCOL.md 1.8). Trusted players only.");
        evalEnabled = cfg
            .getBoolean("evalEnabled", CATEGORY_EVAL, true, "Accept eval and describe requests from the agent.");
        evalTimeoutMillis = cfg.getInt(
            "evalTimeoutMillis",
            CATEGORY_EVAL,
            500,
            10,
            60000,
            "Default time limit of one script. It holds the server tick while it runs.");
        evalMaxTimeoutMillis = cfg.getInt(
            "evalMaxTimeoutMillis",
            CATEGORY_EVAL,
            5000,
            10,
            60000,
            "Largest time limit a request may ask for (timeoutMs).");
        evalMaxResultChars = cfg.getInt(
            "evalMaxResultChars",
            CATEGORY_EVAL,
            64 * 1024,
            1024,
            4 * 1024 * 1024,
            "Approximate size cap of a script's JSON result.");
        evalMaxOutputChars = cfg
            .getInt("evalMaxOutputChars", CATEGORY_EVAL, 16 * 1024, 0, 1024 * 1024, "Printed output kept per script.");
        confirmTimeoutSeconds = cfg.getInt(
            "confirmTimeoutSeconds",
            CATEGORY_EVAL,
            120,
            5,
            3600,
            "How long a world-changing script waits for the player's Confirm click by default.");
        confirmMaxTimeoutSeconds = cfg.getInt(
            "confirmMaxTimeoutSeconds",
            CATEGORY_EVAL,
            600,
            5,
            3600,
            "Longest confirm wait a request may ask for.");
        backupCommand = cfg.getString(
            "backupCommand",
            CATEGORY_EVAL,
            "",
            "Server command run (as the server) right before a confirmed world-changing script, e.g. 'backup start' "
                + "(ServerUtilities; it copies in the background). Empty: none.");
        clientEvalRelay = cfg.getBoolean(
            "clientEvalRelay",
            CATEGORY_EVAL,
            true,
            "Relay client_eval to players who opted in on their own client. Players who did not opt in never get it.");
        clientEvalTimeoutSeconds = cfg.getInt(
            "clientEvalTimeoutSeconds",
            CATEGORY_EVAL,
            30,
            1,
            600,
            "How long to wait for a client's eval result.");
    }

    private static void loadHooks(Configuration cfg) {
        cfg.setCategoryComment(
            CATEGORY_HOOKS,
            "Event hooks: the agent subscribes to game events (joins, deaths, block changes, chat, quests) or to a "
                + "polled Groovy condition, and is told when they fire (PROTOCOL.md 1.9). Saved in the world folder.");
        hooksEnabled = cfg.getBoolean(
            "hooksEnabled",
            CATEGORY_HOOKS,
            true,
            "Accept hook_add and fire hooks. Off: existing hooks are kept but never fire.");
        hookMaxCount = cfg.getInt("hookMaxCount", CATEGORY_HOOKS, 64, 1, 1000, "Most hooks on the server at once.");
        hookMaxPerOwner = cfg
            .getInt("hookMaxPerOwner", CATEGORY_HOOKS, 16, 1, 1000, "Most hooks one player may own at once.");
        hookMinPollTicks = cfg.getInt(
            "hookMinPollTicks",
            CATEGORY_HOOKS,
            20,
            1,
            72000,
            "Shortest interval (ticks) between two runs of one polled condition.");
        hookPollTimeoutMillis = cfg.getInt(
            "hookPollTimeoutMillis",
            CATEGORY_HOOKS,
            50,
            1,
            5000,
            "Time limit of one run of a hook script (condition or filter). It holds the server tick while it runs.");
        hookTickBudgetMillis = cfg.getInt(
            "hookTickBudgetMillis",
            CATEGORY_HOOKS,
            5,
            1,
            45,
            "Time per tick spent on polled conditions; the rest wait for the next tick (at least one runs).");
        hookMaxFiresPerMinute = cfg.getInt(
            "hookMaxFiresPerMinute",
            CATEGORY_HOOKS,
            30,
            1,
            10000,
            "Most hook notifications sent per minute, all hooks together. Extra firings are counted, not sent.");
        hookDefaultCooldownSeconds = cfg.getInt(
            "hookDefaultCooldownSeconds",
            CATEGORY_HOOKS,
            10,
            0,
            86400,
            "Default minimum time between two firings of one hook.");
        hookMaxErrors = cfg.getInt(
            "hookMaxErrors",
            CATEGORY_HOOKS,
            5,
            1,
            1000,
            "A hook whose script fails (throws or times out) this many times in a row is disabled.");
        hookDefaultTtlHours = cfg.getInt(
            "hookDefaultTtlHours",
            CATEGORY_HOOKS,
            168,
            1,
            87600,
            "A hook without expiresInSeconds is removed after this many hours.");
        hookMaxTtlHours = cfg.getInt(
            "hookMaxTtlHours",
            CATEGORY_HOOKS,
            0,
            0,
            876000,
            "Longest lifetime a hook may ask for, in hours. 0: no limit (expiresInSeconds 0 then means never).");
        hookOutboxSize = cfg.getInt(
            "hookOutboxSize",
            CATEGORY_HOOKS,
            50,
            0,
            1000,
            "Hook notifications kept (and saved) while no agent is connected, delivered when one connects.");
    }

    private static void loadUndo(Configuration cfg) {
        cfg.setCategoryComment(
            CATEGORY_UNDO,
            "Undo: before a confirmed world-changing script runs, the blocks it changes are journaled so the change "
                + "can be rolled back (PROTOCOL.md 1.10). Snapshots are saved in the world folder.");
        undoEnabled = cfg
            .getBoolean("undoEnabled", CATEGORY_UNDO, true, "Journal confirmed world changes and accept undo.");
        undoMaxSnapshots = cfg
            .getInt("undoMaxSnapshots", CATEGORY_UNDO, 100, 1, 100000, "Snapshots kept; the oldest go first.");
        undoMaxStorageMB = cfg.getInt(
            "undoMaxStorageMB",
            CATEGORY_UNDO,
            64,
            1,
            100000,
            "Disk space all snapshots may use together (compressed); the oldest go first.");
        undoMaxAgeDays = cfg
            .getInt("undoMaxAgeDays", CATEGORY_UNDO, 30, 0, 36500, "Snapshots older than this are deleted. 0: never.");
        undoMaxBlocksPerSnapshot = cfg.getInt(
            "undoMaxBlocksPerSnapshot",
            CATEGORY_UNDO,
            100000,
            1,
            10000000,
            "Most block positions one snapshot records. A bigger change runs anyway; its snapshot is partial.");
        undoMaxRegionBlocks = cfg.getInt(
            "undoMaxRegionBlocks",
            CATEGORY_UNDO,
            65536,
            1,
            10000000,
            "Largest volume (blocks) of the explicit snapshot regions of one script.");
        undoTickBudgetMillis = cfg.getInt(
            "undoTickBudgetMillis",
            CATEGORY_UNDO,
            10,
            1,
            45,
            "Time per tick spent restoring blocks during an undo (at least one block per tick).");
    }

    private static byte[] readIfPresent(File file) {
        try {
            return file != null && file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        } catch (IOException e) {
            DaveBridge.LOG.warn("Could not read {} before loading it", file, e);
            return null;
        }
    }

    /** Puts the unparseable file back so it can be fixed in place, and keeps the current settings. */
    private static boolean parseFailed(File file, byte[] before, String why) {
        DaveBridge.LOG.error(
            "Could not load {} ({}). Kept the previous settings, including authToken and bindAddress; "
                + "fix the file and run /dave reload.",
            file,
            why);
        if (before != null) {
            try {
                Files.write(file.toPath(), before);
            } catch (IOException e) {
                DaveBridge.LOG.error("Could not restore {}; Forge left a copy next to it as *.errored", file, e);
            }
        }
        if (!loaded) {
            enabled = false;
            DaveBridge.LOG.error("The agent bridge stays off until {} loads.", file);
        }
        return false;
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
