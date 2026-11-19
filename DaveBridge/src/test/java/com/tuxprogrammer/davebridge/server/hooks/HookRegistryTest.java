package com.tuxprogrammer.davebridge.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class HookRegistryTest {

    static HookSpec hook(String event, String owner) throws HookSpec.Invalid {
        return HookSpecTest.parse(
            "{\"event\":\"" + event
                + "\",\"owner\":\""
                + owner
                + "\""
                + ("poll".equals(event) ? ",\"script\":\"true\"" : "")
                + "}");
    }

    @Test
    void idsCapsAndWatchedEvents() throws Exception {
        HookRegistry r = new HookRegistry();
        assertFalse(r.watches("chat"));
        HookSpec a = r.add(hook("chat", "Steve"), 3, 2);
        HookSpec b = r.add(hook("player_join", "steve"), 3, 2);
        assertEquals("h1", a.id);
        assertEquals("h2", b.id);
        assertTrue(r.watches("chat"));
        assertThrows(HookRegistry.Full.class, () -> r.add(hook("chat", "STEVE"), 3, 2), "per-owner cap");
        r.add(hook("chat", "Alex"), 3, 2);
        assertThrows(HookRegistry.Full.class, () -> r.add(hook("chat", "Bob"), 3, 2), "total cap");
        assertEquals(a, r.get("1"));
        assertEquals(a, r.get("H1"));
        a.disabled = true;
        r.refreshActive();
        assertTrue(r.watches("chat"), "Alex's chat hook is still enabled");
        assertEquals(
            1,
            r.of("chat")
                .size());
        List<HookSpec> gone = r.removeOwner("steve");
        assertEquals(2, gone.size());
        assertFalse(r.watches("player_join"));
        assertNull(r.remove("h1"));
        assertEquals("h4", r.add(hook("poll", "Bob"), 3, 2).id, "ids are never reused");
    }

    @Test
    void cooldownAndGlobalRateLimit() throws Exception {
        HookRegistry r = new HookRegistry();
        HookSpec h = r.add(hook("chat", "A"), 10, 10);
        h.cooldownSeconds = 10;
        assertTrue(r.allowFire(h, 1_000, 3));
        h.lastFiredAt = 1_000;
        assertFalse(r.allowFire(h, 5_000, 3), "inside its cooldown");
        assertEquals(1, h.suppressed);
        HookSpec other = r.add(hook("chat", "B"), 10, 10);
        other.cooldownSeconds = 0;
        assertTrue(r.allowFire(other, 2_000, 3));
        assertTrue(r.allowFire(other, 3_000, 3));
        assertFalse(r.allowFire(other, 4_000, 3), "three per minute, all hooks together");
        assertTrue(r.allowFire(other, 62_000, 3), "the window slides");
    }

    @Test
    void expiry() throws Exception {
        HookRegistry r = new HookRegistry();
        HookSpec h = r.add(hook("chat", "A"), 10, 10);
        h.expiresAt = 100;
        r.add(hook("chat", "B"), 10, 10).expiresAt = 0;
        assertEquals(
            0,
            r.expire(50)
                .size());
        assertEquals(
            h,
            r.expire(101)
                .get(0));
        assertEquals(1, r.size());
    }

    @Test
    void outboxIsCappedAndPersisted() throws Exception {
        HookRegistry r = new HookRegistry();
        r.add(hook("chat", "A"), 10, 10);
        for (int i = 0; i < 5; i++) {
            JsonObject m = new JsonObject();
            m.addProperty("type", "hook_fired");
            m.addProperty("n", i);
            r.queue(m, 3);
        }
        assertEquals(3, r.outboxSize());
        String saved = r.toJson();
        HookRegistry back = new HookRegistry();
        back.load(saved);
        assertEquals(1, back.size());
        List<String> out = back.drainOutbox();
        assertEquals(3, out.size());
        assertTrue(
            out.get(0)
                .contains("\"n\":2"),
            "the oldest were dropped");
        assertEquals(0, back.outboxSize());
        assertEquals("h2", back.add(hook("chat", "B"), 10, 10).id, "nextId survives a reload");
    }

    @Test
    void damagedFilesAreRefusedOrCleaned() {
        HookRegistry r = new HookRegistry();
        assertThrows(IllegalArgumentException.class, () -> r.load("[1,2"));
        r.load(
            "{\"hooks\":[{\"id\":\"h7\",\"event\":\"bogus\"},null,{\"id\":\"h9\",\"event\":\"chat\",\"owner\":\"A\"}]}");
        assertEquals(1, r.size());
        assertTrue(r.watches("chat"));
    }
}
