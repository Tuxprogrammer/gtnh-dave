package com.tuxprogrammer.davebridge.common;

import java.util.function.Consumer;

/**
 * The client half's surface-ask implementation, installed by {@code client.ClientProxy}. Lets
 * {@code api.DaveBridgeClientApi} (loaded on both sides) reach client code without referencing a client class: on a
 * dedicated server nothing is installed and the API reports itself unavailable.
 */
public final class SurfaceHook {

    /** What the client half provides. Methods behave as documented on {@code api.DaveBridgeClientApi}. */
    public interface Backend {

        boolean available();

        String ask(String surface, String text, String contextJson, String attachmentJson);

        void cancel(String requestId);

        void listen(String surface, Consumer<String> listener);

        void unlisten(String surface, Consumer<String> listener);
    }

    private static volatile Backend backend;

    private SurfaceHook() {}

    public static void install(Backend b) {
        backend = b;
    }

    /** The installed backend, or null (dedicated server, or before client pre-init). */
    public static Backend get() {
        return backend;
    }
}
