package com.tuxprogrammer.davebridge.api;

import java.util.function.Consumer;

import com.tuxprogrammer.davebridge.client.SurfaceClient;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * Client API for other mods: ask Dave from your own panel ("surface") and get the answers back privately, never in
 * chat. Strings only, so a mod can call it by reflection without compiling against DaveBridge. Client side only; on a
 * dedicated server {@link #available()} is false and the other methods throw {@link IllegalStateException}.
 * <p>
 * Contract (PROTOCOL.md 2.7): {@link #ask} returns a request id; your {@link #listen} callback then gets JSON events on
 * the client thread for that id: {@code thinking} (any number), {@code reply} (one or more; the last has
 * {@code final:true}) or {@code error} (always final). After {@link #cancel} nothing more arrives for the id.
 */
public final class DaveBridgeClientApi {

    private DaveBridgeClientApi() {}

    private static boolean client() {
        return FMLCommonHandler.instance()
            .getSide()
            .isClient();
    }

    /** True when connected to a server whose DaveBridge accepts surface asks. Show your Ask button only then. */
    public static boolean available() {
        return client() && SurfaceClient.available();
    }

    /**
     * Sends a question. {@code surface}: your panel's name ({@code [a-z0-9][a-z0-9_.-]{0,31}}, e.g. "gtnhplanner").
     * {@code contextJson}: a small JSON object shown to Dave (at most 16 KiB), or null. {@code attachmentJson}: a JSON
     * object or array (at most 256 KiB) handed to Dave's tools outside his prompt, or null.
     *
     * @return the request id every event for this question carries
     * @throws IllegalStateException    when not {@link #available()}
     * @throws IllegalArgumentException for a bad surface, empty or too long text, or bad or oversized JSON
     */
    public static String ask(String surface, String text, String contextJson, String attachmentJson) {
        requireClient();
        return SurfaceClient.ask(surface, text, contextJson, attachmentJson);
    }

    /** Stops waiting for {@code requestId}; Dave drops it if it has not started, and later replies are discarded. */
    public static void cancel(String requestId) {
        requireClient();
        SurfaceClient.cancel(requestId);
    }

    /** Registers {@code eventJson} for {@code surface}'s events. Called on the client thread. */
    public static void listen(String surface, Consumer<String> eventJson) {
        requireClient();
        SurfaceClient.listen(surface, eventJson);
    }

    /** Removes a listener added with {@link #listen}. */
    public static void unlisten(String surface, Consumer<String> eventJson) {
        requireClient();
        SurfaceClient.unlisten(surface, eventJson);
    }

    private static void requireClient() {
        if (!client()) {
            throw new IllegalStateException("DaveBridgeClientApi is client-side only");
        }
    }
}
