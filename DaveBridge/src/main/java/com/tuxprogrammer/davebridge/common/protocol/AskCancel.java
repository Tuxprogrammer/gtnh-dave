package com.tuxprogrammer.davebridge.common.protocol;

/**
 * C-&gt;S: the player gave up on a surface ask. The server relays it to the agent as an {@code ask_cancel} event; the
 * agent drops a queued question and discards replies to one already running. Clients send it only to servers with
 * the {@link Surfaces#FEATURE} feature.
 */
public class AskCancel extends DavePacket {

    public static final String KIND = "ask_cancel";

    public String requestId;

    @Override
    public String kind() {
        return KIND;
    }
}
