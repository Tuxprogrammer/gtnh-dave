package com.tuxprogrammer.davebridge.common.protocol;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

/**
 * S-&gt;C: a message from Dave for a client that has the mod.
 * <p>
 * Normal replies arrive as ordinary chat; this packet is used for <b>surface</b> replies (answers to a surface
 * {@link Ask}), which go only to the asker's panel and never to chat. A surface reply is either a reply ({@link #text},
 * {@link #markup}, {@link #components}, {@link #data}) or an {@link #error}. A request may get several replies; the
 * last one has {@link #finalReply} set.
 */
public class DaveMessage extends DavePacket {

    public static final String KIND = "dave_message";

    public String botName;
    /** Plain text of the message (newline-separated lines). */
    public String text;
    /** Id of the chat event this answers, if the agent supplied one. */
    public String replyTo;

    /** Surface replies only: the panel and request this answers. */
    public String surface;
    public String requestId;
    /** Surface replies only: the last message for this request (no more replies or thinking will follow). */
    @SerializedName("final")
    public boolean finalReply;
    /** Surface replies only: the Dave markup the agent sent, if it sent markup. */
    public String markup;
    /** Surface replies only: the rendered message, one vanilla chat-component JSON string per line. */
    public String[] components;
    /** Surface replies only, optional: structured data for the panel (a JSON object), passed through verbatim. */
    public JsonElement data;
    /** Surface replies only: set instead of a reply when the ask failed (refused, timed out, Dave offline). */
    public String error;

    @Override
    public String kind() {
        return KIND;
    }
}
