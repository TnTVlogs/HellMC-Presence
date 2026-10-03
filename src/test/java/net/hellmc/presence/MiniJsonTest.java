package net.hellmc.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {
    @Test
    void parsesNestedObjects() {
        Map<String, Object> m = MiniJson.parseObject("{\"a\":\"x\",\"n\":12,\"b\":true,\"z\":null,\"o\":{\"k\":[1,\"two\"]}}");
        assertEquals("x", m.get("a"));
        assertEquals(12.0, m.get("n"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertTrue(m.containsKey("z"));
        assertEquals(List.of(1.0, "two"), ((Map<?, ?>) m.get("o")).get("k"));
    }

    @Test
    void handlesEscapesAndUnicode() {
        Map<String, Object> m = MiniJson.parseObject("{\"t\":\"a\\\"b\\\\c\\n\\u00e0\"}");
        assertEquals("a\"b\\c\nà", m.get("t"));
    }

    @Test
    void quoteRoundTrips() {
        String raw = "línia \"1\"\n\ttab \\ \u0001";
        Map<String, Object> m = MiniJson.parseObject("{\"v\":" + MiniJson.quote(raw) + "}");
        assertEquals(raw, m.get("v"));
        assertEquals("null", MiniJson.quote(null));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("[1]"));
    }
}
