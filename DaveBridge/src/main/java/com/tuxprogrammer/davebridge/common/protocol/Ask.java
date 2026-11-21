package com.tuxprogrammer.davebridge.common.protocol;

import com.google.gson.JsonElement;

/**
 * C-&gt;S: a question for Dave with client-side context (what the player looks at, holds, has open). The server
 * validates it, rate-limits it and relays it to the agent as a chat event with {@code "source":"client"}. Context is
 * client-reported and untrusted.
 * <p>
 * A <b>surface</b> ask comes from another mod's panel through the client API ({@code api.DaveBridgeClientApi}), not
 * from the chat line: it carries {@link #surface} and {@link #requestId}, is not shown in public chat, and its answer
 * comes back only to the asker as {@link DaveMessage}s with the same request id. Older servers ignore the extra
 * fields, so clients send surface asks only to servers that list {@link Surfaces#FEATURE} in {@code server_hello}.
 */
public class Ask extends DavePacket {

    public static final String KIND = "ask";

    public String text;
    /** Expected to be a JSON object; the server drops anything else. */
    public JsonElement context;
    /** Surface asks only: which panel asked (e.g. {@code "gtnhplanner"}). Null for chat-line asks. */
    public String surface;
    /** Surface asks only: client-chosen id that every reply, thinking and error for this ask carries. */
    public String requestId;
    /** Surface asks only, optional: a JSON object or array handed to the agent outside the prompt (a plan snapshot). */
    public JsonElement attachment;

    @Override
    public String kind() {
        return KIND;
    }
}
