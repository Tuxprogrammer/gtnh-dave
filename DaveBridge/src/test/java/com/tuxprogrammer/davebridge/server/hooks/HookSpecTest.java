package com.tuxprogrammer.davebridge.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class HookSpecTest {

    static final HookSpec.Limits LIMITS = new HookSpec.Limits(20, 10, 168 * 3_600_000L, 0);
    static final long NOW = 1_700_000_000_000L;

    static JsonObject json(String s) {
        return new JsonParser().parse(s)
            .getAsJsonObject();
    }

    static HookSpec parse(String s) throws HookSpec.Invalid {
        return HookSpec.parse(json(s), LIMITS, NOW);
    }

    static Map<String, Object> facts(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void defaults() throws Exception {
        HookSpec h = parse("{\"event\":\"player_join\",\"owner\":\"Steve\",\"note\":\"say hi\"}");
        assertEquals("player_join", h.event);
        assertEquals("player_join", h.name);
        assertEquals("owner", h.notify);
        assertTrue(h.once);
        assertTrue(h.edge);
        assertFalse(h.fireIfTrueAtStart);
        assertEquals(10, h.cooldownSeconds);
        assertEquals(NOW + 168 * 3_600_000L, h.expiresAt);
        assertNull(h.script);
    }

    @Test
    void pollNeedsAScriptAndASaneInterval() {
        assertThrows(HookSpec.Invalid.class, () -> parse("{\"event\":\"poll\",\"owner\":\"Steve\"}"));
        assertThrows(
            HookSpec.Invalid.class,
            () -> parse("{\"event\":\"poll\",\"owner\":\"Steve\",\"script\":\"true\",\"everyTicks\":5}"));
        assertThrows(HookSpec.Invalid.class, () -> parse("{\"event\":\"nope\",\"owner\":\"Steve\"}"));
        assertThrows(HookSpec.Invalid.class, () -> parse("{\"event\":\"chat\"}"));
        assertThrows(
            HookSpec.Invalid.class,
            () -> parse("{\"event\":\"chat\",\"owner\":\"A\",\"filter\":{\"colour\":\"red\"}}"));
        assertThrows(
            HookSpec.Invalid.class,
            () -> parse("{\"event\":\"chat\",\"owner\":\"A\",\"filter\":{\"box\":[1,2,3]}}"));
    }

    @Test
    void expiry() throws Exception {
        assertEquals(0, parse("{\"event\":\"chat\",\"owner\":\"A\",\"expiresInSeconds\":0}").expiresAt);
        assertEquals(NOW + 60_000, parse("{\"event\":\"chat\",\"owner\":\"A\",\"expiresInSeconds\":60}").expiresAt);
        HookSpec.Limits capped = new HookSpec.Limits(20, 10, 3_600_000L, 7_200_000L);
        HookSpec h = HookSpec.parse(json("{\"event\":\"chat\",\"owner\":\"A\",\"expiresInSeconds\":0}"), capped, NOW);
        assertEquals(NOW + 7_200_000L, h.expiresAt, "never is capped by hookMaxTtlHours");
        assertTrue(h.expired(NOW + 7_200_001L));
        assertFalse(h.expired(NOW + 7_199_999L));
    }

    @Test
    void filters() throws Exception {
        HookSpec h = parse(
            "{\"event\":\"block_break\",\"owner\":\"A\",\"filter\":{\"player\":\"steve\",\"block\":\"minecraft:wool:14\","
                + "\"dim\":0,\"box\":[10,0,10,0,255,0]}}");
        assertTrue(
            h.matches(
                facts("player", "Steve", "block", "minecraft:wool", "meta", 14, "dim", 0, "x", 5.0, "y", 64, "z", 3)));
        assertFalse(
            h.matches(
                facts("player", "Steve", "block", "minecraft:wool", "meta", 1, "dim", 0, "x", 5, "y", 64, "z", 3)),
            "meta must match");
        assertFalse(
            h.matches(
                facts("player", "Alex", "block", "minecraft:wool", "meta", 14, "dim", 0, "x", 5, "y", 64, "z", 3)),
            "player must match");
        assertFalse(
            h.matches(
                facts("player", "Steve", "block", "minecraft:wool", "meta", 14, "dim", 0, "x", 11, "y", 64, "z", 3)),
            "outside the box");
        assertTrue(HookSpec.blockMatches("stone", "minecraft:stone", 0), "a bare name is minecraft:");
        assertTrue(HookSpec.blockMatches("gregtech:gt.blockmachines", "gregtech:gt.blockmachines", 7));

        HookSpec boss = parse(
            "{\"event\":\"entity_death\",\"owner\":\"A\",\"filter\":{\"entity\":\"wither\","
                + "\"player\":\"Steve\"}}");
        assertTrue(boss.matches(facts("entity", "WitherBoss", "killer", "Steve")));
        assertFalse(boss.matches(facts("entity", "Zombie", "killer", "Steve")));
        assertFalse(boss.matches(facts("entity", "WitherBoss", "player", "Steve")), "entity_death matches the killer");

        HookSpec chat = parse("{\"event\":\"chat\",\"owner\":\"A\",\"filter\":{\"contains\":\"Help\"}}");
        assertTrue(chat.matches(facts("message", "can someone HELP me")));
        assertFalse(chat.matches(facts("message", "hello")));

        HookSpec quest = parse("{\"event\":\"quest_complete\",\"owner\":\"A\",\"filter\":{\"quest\":\"steam age\"}}");
        assertTrue(
            quest.matches(facts("questIds", Arrays.asList("x"), "questNames", Arrays.asList("§6The Steam Age"))));
        assertFalse(quest.matches(facts("questIds", Arrays.asList("x"), "questNames", Arrays.asList("LV"))));
        assertTrue(parse("{\"event\":\"chat\",\"owner\":\"A\"}").matches(facts()), "no filter matches everything");
    }

    @Test
    void jsonRoundTripKeepsState() throws Exception {
        HookSpec h = parse(
            "{\"event\":\"poll\",\"owner\":\"A\",\"script\":\"true\",\"params\":{\"x\":1},"
                + "\"everyTicks\":40,\"once\":false}");
        h.id = "h3";
        h.lastValue = true;
        h.fireCount = 2;
        HookRegistry r = new HookRegistry();
        r.load("{\"nextId\":4,\"hooks\":[" + h.toJson() + "]}");
        HookSpec back = r.get("h3");
        assertEquals(40, back.everyTicks);
        assertFalse(back.once);
        assertEquals(Boolean.TRUE, back.lastValue);
        assertEquals(2, back.fireCount);
        assertEquals(
            1.0,
            back.params.getAsJsonObject()
                .get("x")
                .getAsDouble());
    }
}
