package com.tuxprogrammer.davebridge.common.protocol;

/**
 * S-&gt;C: Dave is working on an answer. Clients with the mod show a transient indicator. With {@link #surface} and
 * {@link #requestId} set it belongs to a surface ask and goes to that panel instead of the chat indicator.
 */
public class Thinking extends DavePacket {

    public static final String KIND = "thinking";

    public String botName;
    /** True when Dave's answer has arrived (as chat): remove the indicator instead of showing it. */
    public boolean clear;
    /** Set for a surface ask's indicator (see {@link Ask#surface}). */
    public String surface;
    public String requestId;

    @Override
    public String kind() {
        return KIND;
    }
}
