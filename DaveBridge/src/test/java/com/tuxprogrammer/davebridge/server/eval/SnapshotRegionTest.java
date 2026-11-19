package com.tuxprogrammer.davebridge.server.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.tuxprogrammer.davebridge.server.bridge.ProtocolException;

/** The {@code snapshot} field of a write eval: explicit regions to save before the script runs. */
class SnapshotRegionTest {

    static List<EvalService.Region> parse(String json) throws ProtocolException {
        return EvalService.parseRegions(json == null ? null : new JsonParser().parse(json));
    }

    @Test
    void oneOrManyRegions() throws Exception {
        assertTrue(parse(null).isEmpty());
        List<EvalService.Region> one = parse("{\"dim\":-1,\"from\":[1.7,64,-3.2],\"to\":[3,66,-1]}");
        assertEquals(1, one.size());
        assertEquals(-1, one.get(0).dim);
        assertArrayEquals(new int[] { 1, 64, -4 }, one.get(0).from, "coordinates are floored");
        assertEquals(
            3 * 3 * 4,
            one.get(0)
                .volume());
        List<EvalService.Region> two = parse("[{\"from\":[0,0,0],\"to\":[0,0,0]},{\"from\":[5,5,5],\"to\":[4,4,4]}]");
        assertEquals(2, two.size());
        assertEquals(Integer.MIN_VALUE, two.get(0).dim, "no dim: the asker's");
        assertEquals(
            8,
            two.get(1)
                .volume());
    }

    @Test
    void badRegionsAreRefused() {
        assertThrows(ProtocolException.class, () -> parse("5"));
        assertThrows(ProtocolException.class, () -> parse("{\"from\":[1,2],\"to\":[1,2,3]}"));
        assertThrows(ProtocolException.class, () -> parse("{\"to\":[1,2,3]}"));
        assertThrows(
            ProtocolException.class,
            () -> parse("{\"from\":[0,0,0],\"to\":[1000,255,1000]}"),
            "over undoMaxRegionBlocks");
    }
}
