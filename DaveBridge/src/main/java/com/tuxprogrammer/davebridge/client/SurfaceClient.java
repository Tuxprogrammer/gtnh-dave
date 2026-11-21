package com.tuxprogrammer.davebridge.client;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tuxprogrammer.davebridge.DaveBridge;
import com.tuxprogrammer.davebridge.common.network.DaveNetwork;
import com.tuxprogrammer.davebridge.common.protocol.Ask;
import com.tuxprogrammer.davebridge.common.protocol.AskCancel;
import com.tuxprogrammer.davebridge.common.protocol.DaveMessage;
import com.tuxprogrammer.davebridge.common.protocol.Surfaces;
import com.tuxprogrammer.davebridge.common.protocol.Thinking;

/**
 * Surface asks on the client: questions another mod's panel sends through {@code api.DaveBridgeClientApi}, and the
 * replies routed back to that panel's listeners instead of chat (PROTOCOL.md 2.7).
 * <p>
 * Reached from {@code api.DaveBridgeClientApi} through {@code common.SurfaceHook}, installed by {@link ClientProxy}.
 * Events
 * are delivered on the client thread (packet handlers queue them on {@link ClientProxy#QUEUE}).
 */
public final class SurfaceClient {

    /** A request with no final reply after this long is ended with an error (turn plus follow-up caps, with margin). */
    static final long REQUEST_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, List<Consumer<String>>> LISTENERS = new ConcurrentHashMap<>();
    /** requestId -> surface and start time, for requests still waiting for their final reply. */
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    private static final class Pending {

        final String surface;
        final long started;

        Pending(String surface, long started) {
            this.surface = surface;
            this.started = started;
        }
    }

    private SurfaceClient() {}

    /** The server runs DaveBridge with surface support and we are connected to it. */
    public static boolean available() {
        return ClientState.serverHasMod && ClientState.serverSurfaces;
    }

    /**
     * Sends a surface ask and returns its request id. Throws {@link IllegalStateException} when not
     * {@link #available()}, {@link IllegalArgumentException} for a bad surface name, empty or too long text, JSON that
     * does not parse, a context that is not an object, or an attachment that is not an object or array or is too big.
     */
    public static String ask(String surface, String text, String contextJson, String attachmentJson) {
        if (!available()) {
            throw new IllegalStateException("this server does not run a DaveBridge with surface support");
        }
        Ask ask = buildAsk(surface, text, contextJson, attachmentJson, ClientState.maxAskLength, newRequestId());
        PENDING.put(ask.requestId, new Pending(surface, System.nanoTime()));
        DaveNetwork.sendToServer(ask);
        return ask.requestId;
    }

    /** Validates and builds the packet (no I/O). Package-private for tests. */
    static Ask buildAsk(String surface, String text, String contextJson, String attachmentJson, int maxAskLength,
        String requestId) {
        if (!Surfaces.validSurface(surface)) {
            throw new IllegalArgumentException("bad surface name: " + surface);
        }
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("the question is empty");
        }
        if (trimmed.length() > maxAskLength) {
            throw new IllegalArgumentException(
                "the question is too long (" + trimmed.length() + " characters, at most " + maxAskLength + ")");
        }
        JsonElement context = parse(contextJson, "contextJson");
        if (context != null && !context.isJsonObject()) {
            throw new IllegalArgumentException("contextJson must be a JSON object");
        }
        if (context != null && context.toString()
            .length() > 16 * 1024) {
            throw new IllegalArgumentException("contextJson is over 16 KiB");
        }
        JsonElement attachment = parse(attachmentJson, "attachmentJson");
        String bad = Surfaces.checkAttachment(attachment);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        Ask ask = new Ask();
        ask.surface = surface;
        ask.requestId = requestId;
        ask.text = trimmed;
        ask.context = context;
        ask.attachment = attachment;
        return ask;
    }

    /** Stops waiting for {@code requestId}: later replies are dropped, and the server is told so Dave can drop it. */
    public static void cancel(String requestId) {
        if (requestId == null || PENDING.remove(requestId) == null) {
            return;
        }
        AskCancel cancel = new AskCancel();
        cancel.requestId = requestId;
        DaveNetwork.sendToServer(cancel);
    }

    /** Adds a listener for {@code surface}'s events (event JSON strings, PROTOCOL.md 2.7). Client thread. */
    public static void listen(String surface, Consumer<String> listener) {
        if (!Surfaces.validSurface(surface)) {
            throw new IllegalArgumentException("bad surface name: " + surface);
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener is null");
        }
        LISTENERS.computeIfAbsent(surface, s -> new CopyOnWriteArrayList<>())
            .add(listener);
    }

    /** Removes a listener added with {@link #listen}. */
    public static void unlisten(String surface, Consumer<String> listener) {
        List<Consumer<String>> list = surface == null ? null : LISTENERS.get(surface);
        if (list != null) {
            list.remove(listener);
        }
    }

    // ------------------------------------------------------------------ packets (client thread)

    static void onMessage(DaveMessage message) {
        Pending pending = PENDING.get(message.requestId);
        if (pending == null || !pending.surface.equals(message.surface)) {
            return; // cancelled, timed out, or never ours
        }
        JsonObject event = eventFor(message);
        if (event.get("final")
            .getAsBoolean()) {
            PENDING.remove(message.requestId);
        }
        emit(message.surface, event);
    }

    static void onThinking(Thinking thinking) {
        Pending pending = PENDING.get(thinking.requestId);
        if (pending == null || !pending.surface.equals(thinking.surface)) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", "thinking");
        event.addProperty("surface", thinking.surface);
        event.addProperty("requestId", thinking.requestId);
        emit(thinking.surface, event);
    }

    /** The event JSON for a surface {@link DaveMessage}. Package-private for tests. */
    static JsonObject eventFor(DaveMessage message) {
        JsonObject event = new JsonObject();
        boolean error = message.error != null;
        event.addProperty("type", error ? "error" : "reply");
        event.addProperty("surface", message.surface);
        event.addProperty("requestId", message.requestId);
        event.addProperty("final", error || message.finalReply);
        if (error) {
            event.addProperty("message", message.error);
            return event;
        }
        event.addProperty("text", message.text == null ? "" : message.text);
        if (message.markup != null) {
            event.addProperty("markup", message.markup);
        }
        JsonArray lines = new JsonArray();
        if (message.components != null) {
            for (String line : message.components) {
                if (line != null) {
                    lines.add(new JsonPrimitive(line));
                }
            }
        }
        event.add("componentsJson", lines);
        if (message.data != null && message.data.isJsonObject()) {
            event.add("data", message.data);
        }
        return event;
    }

    /** Ends requests that waited too long. Client tick. */
    static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (Iterator<Map.Entry<String, Pending>> it = PENDING.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<String, Pending> e = it.next();
            if (now - e.getValue().started > REQUEST_TIMEOUT_NANOS) {
                it.remove();
                emit(
                    e.getValue().surface,
                    errorEvent(e.getValue().surface, e.getKey(), "Dave did not answer in time."));
            }
        }
    }

    /** The connection ended: every waiting request fails. Client thread. */
    static void failAll(String why) {
        for (Iterator<Map.Entry<String, Pending>> it = PENDING.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<String, Pending> e = it.next();
            it.remove();
            emit(e.getValue().surface, errorEvent(e.getValue().surface, e.getKey(), why));
        }
    }

    static JsonObject errorEvent(String surface, String requestId, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "error");
        event.addProperty("surface", surface);
        event.addProperty("requestId", requestId);
        event.addProperty("final", true);
        event.addProperty("message", message);
        return event;
    }

    private static void emit(String surface, JsonObject event) {
        List<Consumer<String>> list = LISTENERS.get(surface);
        if (list == null) {
            return;
        }
        String json = event.toString();
        for (Consumer<String> listener : list) {
            try {
                listener.accept(json);
            } catch (Throwable t) {
                DaveBridge.LOG.warn("A {} listener failed on a DaveBridge event", surface, t);
            }
        }
    }

    private static JsonElement parse(String json, String what) {
        if (json == null || json.trim()
            .isEmpty()) {
            return null;
        }
        try {
            JsonElement el = new JsonParser().parse(json);
            return el.isJsonNull() ? null : el;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException(what + " is not valid JSON: " + e.getMessage());
        }
    }

    static String newRequestId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("r");
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    /** Test hook: forget all state. */
    static void clearForTests() {
        PENDING.clear();
        LISTENERS.clear();
    }

    /** Test hook: mark a request as waiting without sending anything. */
    static void trackForTests(String surface, String requestId) {
        PENDING.put(requestId, new Pending(surface, System.nanoTime()));
    }
}
