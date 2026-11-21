package com.tuxprogrammer.davebridge.server.chat;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tuxprogrammer.davebridge.common.protocol.Surfaces;
import com.tuxprogrammer.davebridge.server.PlayerJson;
import com.tuxprogrammer.davebridge.server.bridge.MessageRouter;
import com.tuxprogrammer.davebridge.server.bridge.Protocol;
import com.tuxprogrammer.davebridge.server.bridge.ProtocolException;
import com.tuxprogrammer.davebridge.server.bridge.Request;

/** Milestone 1 agent actions: {@code say}, {@code thinking}, {@code list_players}. */
public final class ChatHandlers {

    private ChatHandlers() {}

    public static void register(MessageRouter router) {
        router.register("say", request -> {
            String target = request.optString("target", "@a");
            String text = request.optString("text", null);
            String markup = request.optString("markup", null);
            JsonElement components = request.body()
                .get("components");
            if (components != null && components.isJsonNull()) components = null;
            int given = (text != null ? 1 : 0) + (markup != null ? 1 : 0) + (components != null ? 1 : 0);
            if (given != 1 && request.body()
                .get("surface") == null) {
                throw new ProtocolException(
                    Protocol.ERR_BAD_REQUEST,
                    "say needs exactly one of 'text', 'markup' or 'components'");
            }
            String replyTo = request.optString("replyTo", null);
            JsonElement comps = components;
            String surface = request.optString("surface", null);
            String requestId = request.optString("requestId", null);
            String error = request.optString("error", null);
            JsonElement data = request.body()
                .get("data");
            if (data != null && data.isJsonNull()) data = null;
            boolean isFinal = !request.body()
                .has("final") || request.body()
                    .get("final")
                    .getAsBoolean();
            if (surface != null || requestId != null) {
                checkSurfaceSay(surface, requestId, given + (error != null ? 1 : 0), data);
            } else if (error != null || data != null) {
                throw new ProtocolException(
                    Protocol.ERR_BAD_REQUEST,
                    "'error' and 'data' need 'surface' and 'requestId'");
            }
            JsonElement surfaceData = data;
            router.runOnMainThread(request, "say", () -> {
                DaveChat.SayResult result = surface != null
                    ? DaveChat.saySurface(target, text, markup, comps, error, surface, requestId, isFinal, surfaceData)
                    : DaveChat.say(target, text, markup, comps, replyTo);
                JsonObject ack = withRecipients(request, result.recipients);
                if (ack != null) {
                    JsonArray warnings = new JsonArray();
                    for (String w : result.warnings) warnings.add(new JsonPrimitive(w));
                    ack.add("warnings", warnings);
                }
                for (String w : result.warnings) {
                    com.tuxprogrammer.davebridge.DaveBridge.LOG.info("say warning: {}", w);
                }
                return ack;
            });
        });

        router.register("thinking", request -> {
            String target = request.optString("target", "@a");
            String surface = request.optString("surface", null);
            String requestId = request.optString("requestId", null);
            if (surface != null || requestId != null) {
                if (!Surfaces.validSurface(surface) || !Surfaces.validRequestId(requestId)) {
                    throw new ProtocolException(Protocol.ERR_BAD_REQUEST, "bad 'surface' or 'requestId'");
                }
                router.runOnMainThread(
                    request,
                    "thinking",
                    () -> withRecipients(request, DaveChat.thinkingSurface(target, surface, requestId)));
                return;
            }
            router.runOnMainThread(request, "thinking", () -> withRecipients(request, DaveChat.thinking(target)));
        });

        router.register("list_players", request -> router.runOnMainThread(request, "list_players", () -> {
            List<EntityPlayerMP> online = DaveChat.onlinePlayers();
            JsonArray players = new JsonArray();
            for (EntityPlayerMP player : online) {
                players.add(PlayerJson.describe(player, new JsonObject()));
            }
            JsonObject reply = Protocol.message("players");
            reply.add("players", players);
            return reply;
        }));
    }

    /**
     * Validates a surface {@code say}: both ids well formed, exactly one of text / markup / components / error, and
     * {@code data} (if any) a JSON object within {@link Surfaces#MAX_DATA_BYTES}. A reply may carry only data.
     */
    static void checkSurfaceSay(String surface, String requestId, int contents, JsonElement data)
        throws ProtocolException {
        if (!Surfaces.validSurface(surface) || !Surfaces.validRequestId(requestId)) {
            throw new ProtocolException(
                Protocol.ERR_BAD_REQUEST,
                "a surface say needs a valid 'surface' and 'requestId'");
        }
        if (contents > 1 || (contents == 0 && data == null)) {
            throw new ProtocolException(
                Protocol.ERR_BAD_REQUEST,
                "a surface say needs exactly one of 'text', 'markup', 'components' or 'error' (or only 'data')");
        }
        String bad = Surfaces.checkData(data);
        if (bad != null) {
            throw new ProtocolException(Protocol.ERR_BAD_REQUEST, bad);
        }
    }

    private static JsonObject withRecipients(Request request, int recipients) {
        JsonObject ack = request.ackMessage();
        if (ack != null) {
            ack.addProperty("recipients", recipients);
        }
        return ack;
    }
}
