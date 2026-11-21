package com.tuxprogrammer.davebridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tuxprogrammer.davebridge.common.protocol.Ask;
import com.tuxprogrammer.davebridge.common.protocol.DaveMessage;
import com.tuxprogrammer.davebridge.common.protocol.Thinking;

/** Surface asks: validation, event JSON and routing to the right listener. No network or game classes. */
class SurfaceClientTest {

    @AfterEach
    void reset() {
        SurfaceClient.clearForTests();
    }

    private static JsonObject parse(String json) {
        return new JsonParser().parse(json)
            .getAsJsonObject();
    }

    @Test
    void buildAskValidates() {
        Ask ask = SurfaceClient.buildAsk("gtnhplanner", "  best source of ammonia?  ", "{\"planName\":\"Ti\"}",
            "{\"id\":\"plan-1\",\"cards\":[]}", 2000, "r1");
        assertEquals("best source of ammonia?", ask.text);
        assertEquals("Ti", ask.context.getAsJsonObject()
            .get("planName")
            .getAsString());
        assertEquals("plan-1", ask.attachment.getAsJsonObject()
            .get("id")
            .getAsString());
        Ask bare = SurfaceClient.buildAsk("gtnhplanner", "hi", null, "", 2000, "r2");
        assertNull(bare.context);
        assertNull(bare.attachment);

        assertThrows(IllegalArgumentException.class, () -> SurfaceClient.buildAsk("Bad Name", "q", null, null, 2000, "r"));
        assertThrows(IllegalArgumentException.class, () -> SurfaceClient.buildAsk("p", "   ", null, null, 2000, "r"));
        assertThrows(IllegalArgumentException.class, () -> SurfaceClient.buildAsk("p", "too long", null, null, 3, "r"));
        assertThrows(IllegalArgumentException.class, () -> SurfaceClient.buildAsk("p", "q", "[1]", null, 2000, "r"));
        assertThrows(IllegalArgumentException.class, () -> SurfaceClient.buildAsk("p", "q", "{oops", null, 2000, "r"));
        assertThrows(IllegalArgumentException.class, () -> SurfaceClient.buildAsk("p", "q", null, "\"str\"", 2000, "r"));
    }

    @Test
    void requestIdsAreValidAndDistinct() {
        String a = SurfaceClient.newRequestId(), b = SurfaceClient.newRequestId();
        assertTrue(com.tuxprogrammer.davebridge.common.protocol.Surfaces.validRequestId(a), a);
        assertFalse(a.equals(b));
    }

    @Test
    void replyAndErrorEvents() {
        DaveMessage m = new DaveMessage();
        m.surface = "gtnhplanner";
        m.requestId = "r1";
        m.text = "Ammonia: Haber process";
        m.markup = "Ammonia: {m:Large Chemical Reactor}";
        m.components = new String[] { "{\"text\":\"Ammonia\"}" };
        JsonObject data = new JsonObject();
        data.addProperty("proposalId", "p1");
        m.data = data;
        JsonObject ev = SurfaceClient.eventFor(m);
        assertEquals("reply", ev.get("type")
            .getAsString());
        assertFalse(ev.get("final")
            .getAsBoolean());
        assertEquals("p1", ev.getAsJsonObject("data")
            .get("proposalId")
            .getAsString());
        assertEquals(1, ev.getAsJsonArray("componentsJson")
            .size());

        DaveMessage e = new DaveMessage();
        e.surface = "gtnhplanner";
        e.requestId = "r1";
        e.error = "Dave is not connected right now.";
        JsonObject err = SurfaceClient.eventFor(e);
        assertEquals("error", err.get("type")
            .getAsString());
        assertTrue(err.get("final")
            .getAsBoolean());
        assertEquals("Dave is not connected right now.", err.get("message")
            .getAsString());
    }

    @Test
    void routesOnlyTrackedRequestsAndStopsAfterFinal() {
        List<String> got = new ArrayList<>();
        List<String> other = new ArrayList<>();
        SurfaceClient.listen("gtnhplanner", got::add);
        SurfaceClient.listen("othermod", other::add);
        SurfaceClient.trackForTests("gtnhplanner", "r1");

        Thinking t = new Thinking();
        t.surface = "gtnhplanner";
        t.requestId = "r1";
        SurfaceClient.onThinking(t);

        DaveMessage interim = new DaveMessage();
        interim.surface = "gtnhplanner";
        interim.requestId = "r1";
        interim.text = "On it.";
        SurfaceClient.onMessage(interim);

        DaveMessage last = new DaveMessage();
        last.surface = "gtnhplanner";
        last.requestId = "r1";
        last.text = "Done.";
        last.finalReply = true;
        SurfaceClient.onMessage(last);
        SurfaceClient.onMessage(last); // after final: dropped

        DaveMessage stranger = new DaveMessage();
        stranger.surface = "gtnhplanner";
        stranger.requestId = "never-asked";
        stranger.text = "?";
        SurfaceClient.onMessage(stranger);

        assertEquals(3, got.size(), got.toString());
        assertEquals("thinking", parse(got.get(0)).get("type")
            .getAsString());
        assertFalse(parse(got.get(1)).get("final")
            .getAsBoolean());
        assertTrue(parse(got.get(2)).get("final")
            .getAsBoolean());
        assertTrue(other.isEmpty());
    }

    @Test
    void failAllEndsWaitingRequests() {
        List<String> got = new ArrayList<>();
        SurfaceClient.listen("gtnhplanner", got::add);
        SurfaceClient.trackForTests("gtnhplanner", "r1");
        SurfaceClient.trackForTests("gtnhplanner", "r2");
        SurfaceClient.failAll("Disconnected from the server.");
        assertEquals(2, got.size());
        for (String s : got) {
            JsonObject ev = parse(s);
            assertEquals("error", ev.get("type")
                .getAsString());
            assertTrue(ev.get("final")
                .getAsBoolean());
        }
        SurfaceClient.failAll("again");
        assertEquals(2, got.size());
    }

    @Test
    void aThrowingListenerDoesNotStopOthers() {
        List<String> got = new ArrayList<>();
        SurfaceClient.listen("gtnhplanner", s -> {
            throw new RuntimeException("panel bug");
        });
        SurfaceClient.listen("gtnhplanner", got::add);
        SurfaceClient.trackForTests("gtnhplanner", "r1");
        SurfaceClient.failAll("bye");
        assertEquals(1, got.size());
    }
}
