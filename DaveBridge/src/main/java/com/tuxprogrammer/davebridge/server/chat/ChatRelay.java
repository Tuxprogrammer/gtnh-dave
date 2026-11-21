package com.tuxprogrammer.davebridge.server.chat;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuxprogrammer.davebridge.common.Config;
import com.tuxprogrammer.davebridge.server.DaveServer;
import com.tuxprogrammer.davebridge.server.PlayerJson;
import com.tuxprogrammer.davebridge.server.bridge.Protocol;

/** Builds and sends {@code chat} events to the agent, for both plain chat and client-mod asks. */
public final class ChatRelay {

    private ChatRelay() {}

    /**
     * Forwards a player's message to connected agents.
     *
     * @param source    {@code "chat"} for normal chat, {@code "client"} for an Ask from the client half
     * @param triggered whether the message addressed Dave
     * @param prompt    the message without its trigger prefix, or null when not triggered
     * @param context   client-reported context (untrusted), or null
     * @return the number of agents that received it
     */
    public static int relay(EntityPlayerMP player, String message, String source, boolean triggered, String prompt,
        JsonElement context) {
        return relay(player, message, source, triggered, prompt, context, null);
    }

    /**
     * As {@link #relay(EntityPlayerMP, String, String, boolean, String, JsonElement)}, with {@code extra} fields copied
     * into the event (a surface ask's {@code surface}, {@code requestId} and {@code attachment}). A surface ask that
     * reaches no agent is answered with a surface error instead of the chat notice.
     */
    public static int relay(EntityPlayerMP player, String message, String source, boolean triggered, String prompt,
        JsonElement context, JsonObject extra) {
        JsonObject event = Protocol.message("chat");
        event.addProperty(
            "id",
            UUID.randomUUID()
                .toString());
        PlayerJson.describe(player, event);
        event.addProperty("message", message);
        event.addProperty("triggered", triggered);
        if (prompt != null) {
            event.addProperty("prompt", prompt);
        }
        event.addProperty("source", source);
        if (context != null) {
            event.add("context", context);
        }
        if (extra != null) {
            for (java.util.Map.Entry<String, JsonElement> e : extra.entrySet()) {
                event.add(e.getKey(), e.getValue());
            }
        }
        int sent = DaveServer.BRIDGE.broadcast(event);
        if (triggered && sent == 0) {
            String offline = Config.botName + " is not connected right now.";
            if (extra != null && extra.has("surface") && extra.has("requestId")) {
                DaveChat.surfaceError(
                    player,
                    extra.get("surface")
                        .getAsString(),
                    extra.get("requestId")
                        .getAsString(),
                    offline);
            } else if (Config.notifyWhenOffline) {
                player.addChatMessage(DaveChat.notice(offline));
            }
        }
        return sent;
    }
}
