package com.tuxprogrammer.davebridge.common.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/** {@link ScriptEngine#evaluate}: the raw outcome hook conditions use (Groovy truth, failures, time limit). */
class ScriptOutcomeTest {

    private ScriptEngine.Outcome eval(String source, long timeoutMs) throws Exception {
        ScriptEngine.Compiled c = ScriptEngine.shared()
            .compileAsync(source)
            .get(60, TimeUnit.SECONDS);
        Map<String, Object> b = new HashMap<>();
        b.put("state", new HashMap<String, Object>());
        return ScriptEngine.shared()
            .evaluate(c, b, timeoutMs, 100);
    }

    @Test
    void groovyTruth() throws Exception {
        assertTrue(eval("true", 500).truthy());
        assertFalse(eval("false", 500).truthy());
        assertFalse(eval("null", 500).truthy());
        assertFalse(eval("0", 500).truthy());
        assertTrue(eval("3", 500).truthy());
        assertFalse(eval("[]", 500).truthy());
        assertTrue(eval("[stock: 12]", 500).truthy(), "a non-empty map is true and goes out as the detail");
        assertFalse(eval("''", 500).truthy());
    }

    @Test
    void failuresAreCaptured() throws Exception {
        ScriptEngine.Outcome o = eval("def x = null; x.foo()", 500);
        assertFalse(o.ok());
        assertFalse(o.truthy());
        assertEquals("exception", o.errorType());
        assertTrue(
            o.error()
                .contains("NullPointerException"));
        JsonObject j = new JsonObject();
        o.describeFailure(j);
        assertEquals(
            1,
            j.get("line")
                .getAsInt());
        ScriptEngine.Outcome t = eval("while (true) { }", 30);
        assertEquals("timeout", t.errorType());
    }

    @Test
    void stateCarriesAcrossRuns() throws Exception {
        ScriptEngine.Compiled c = ScriptEngine.shared()
            .compileAsync("state.n = (state.n ?: 0) + 1; state.n >= 3")
            .get(60, TimeUnit.SECONDS);
        Map<String, Object> b = new HashMap<>();
        b.put("state", new HashMap<String, Object>());
        assertFalse(
            ScriptEngine.shared()
                .evaluate(c, b, 500, 100)
                .truthy());
        assertFalse(
            ScriptEngine.shared()
                .evaluate(c, b, 500, 100)
                .truthy());
        assertTrue(
            ScriptEngine.shared()
                .evaluate(c, b, 500, 100)
                .truthy());
    }
}
