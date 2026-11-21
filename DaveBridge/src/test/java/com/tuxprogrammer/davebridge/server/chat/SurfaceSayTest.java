package com.tuxprogrammer.davebridge.server.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tuxprogrammer.davebridge.server.bridge.ProtocolException;

/** Surface replies: never public chat, panel only for surface-capable clients, and the say request checks. */
class SurfaceSayTest {

    @Test
    void surfaceRepliesNeverGoToEveryone() {
        assertEquals(DaveChat.SurfaceRoute.REFUSED, DaveChat.surfaceRoute("@a", true, true));
        assertEquals(DaveChat.SurfaceRoute.REFUSED, DaveChat.surfaceRoute("", true, true));
        assertEquals(DaveChat.SurfaceRoute.REFUSED, DaveChat.surfaceRoute(null, true, true));
    }

    @Test
    void routesToThePanelOnlyForSurfaceClients() {
        assertEquals(DaveChat.SurfaceRoute.PANEL, DaveChat.surfaceRoute("Alex", true, true));
        // An older client half (no "surface" capability) or no client half: a private whisper, still not public.
        assertEquals(DaveChat.SurfaceRoute.WHISPER, DaveChat.surfaceRoute("Alex", true, false));
        assertEquals(DaveChat.SurfaceRoute.DROPPED, DaveChat.surfaceRoute("Alex", false, false));
    }

    @Test
    void surfaceSayChecks() {
        JsonObject data = new JsonObject();
        data.addProperty("proposalId", "p1");
        assertDoesNotThrow(() -> ChatHandlers.checkSurfaceSay("gtnhplanner", "r1", 1, null));
        assertDoesNotThrow(() -> ChatHandlers.checkSurfaceSay("gtnhplanner", "r1", 1, data));
        assertDoesNotThrow(() -> ChatHandlers.checkSurfaceSay("gtnhplanner", "r1", 0, data)); // data only
        assertThrows(ProtocolException.class, () -> ChatHandlers.checkSurfaceSay("gtnhplanner", "r1", 0, null));
        assertThrows(ProtocolException.class, () -> ChatHandlers.checkSurfaceSay("gtnhplanner", "r1", 2, null));
        assertThrows(ProtocolException.class, () -> ChatHandlers.checkSurfaceSay(null, "r1", 1, null));
        assertThrows(ProtocolException.class, () -> ChatHandlers.checkSurfaceSay("gtnhplanner", null, 1, null));
        assertThrows(ProtocolException.class, () -> ChatHandlers.checkSurfaceSay("GTNH Planner", "r1", 1, null));
        assertThrows(
            ProtocolException.class,
            () -> ChatHandlers.checkSurfaceSay("gtnhplanner", "r1", 1, new JsonArray()));
    }
}
