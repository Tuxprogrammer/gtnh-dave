package com.tuxprogrammer.davebridge.server.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tuxprogrammer.davebridge.common.protocol.Ask;
import com.tuxprogrammer.davebridge.common.protocol.DavePackets;
import com.tuxprogrammer.davebridge.common.protocol.Surfaces;
import com.tuxprogrammer.davebridge.server.ClientModTracker;

/** Surface asks on the server: text and attachment checks, and which clients count as surface-capable. */
class SurfaceAskTest {

    private static Ask ask(String text) {
        Ask a = new Ask();
        a.surface = "gtnhplanner";
        a.requestId = "r1";
        a.text = text;
        return a;
    }

    @Test
    void textChecks() {
        assertNull(ServerPacketHandlers.surfaceAskError(ask("which ammonia line?"), 1000));
        assertNotNull(ServerPacketHandlers.surfaceAskError(ask("  "), 1000));
        assertNotNull(ServerPacketHandlers.surfaceAskError(ask(null), 1000));
        assertNotNull(ServerPacketHandlers.surfaceAskError(ask("long question"), 5));
    }

    @Test
    void attachmentChecks() {
        Ask a = ask("q");
        JsonObject plan = new JsonObject();
        plan.addProperty("id", "plan-1");
        plan.add("cards", new JsonArray());
        a.attachment = plan;
        assertNull(ServerPacketHandlers.surfaceAskError(a, 1000));
        a.attachment = new JsonPrimitive(3);
        assertNotNull(ServerPacketHandlers.surfaceAskError(a, 1000));
        JsonObject big = new JsonObject();
        big.addProperty("blob", new String(new char[Surfaces.MAX_ATTACHMENT_BYTES]).replace('\0', 'x'));
        a.attachment = big;
        assertTrue(
            ServerPacketHandlers.surfaceAskError(a, 1000)
                .contains("too large"));
    }

    @Test
    void onlyClientsReportingSurfaceAreSurfaceCapable() {
        ClientModTracker t = new ClientModTracker();
        UUID modern = UUID.randomUUID(), older = UUID.randomUUID(), plain = UUID.randomUUID(),
            otherProtocol = UUID.randomUUID();
        t.put(modern, "1", DavePackets.PROTOCOL_VERSION, new String[] { "render", "ask", "surface" }, false);
        t.put(older, "1", DavePackets.PROTOCOL_VERSION, new String[] { "render", "ask" }, false);
        t.put(otherProtocol, "1", DavePackets.PROTOCOL_VERSION + 1, new String[] { "surface" }, false);
        assertTrue(t.hasCapability(modern, Surfaces.FEATURE));
        assertFalse(t.hasCapability(older, Surfaces.FEATURE));
        assertFalse(t.hasCapability(plain, Surfaces.FEATURE));
        assertFalse(t.hasCapability(otherProtocol, Surfaces.FEATURE));
    }
}
