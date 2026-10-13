package io.vorg.simulation.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesAndWritesCanonically() {
        String text = " { \"b\": 1, \"a\": [true, false, null, 2.50, -3, 1e2], \"s\": \"x\\\"y\\n\\u00e9\" } ";
        Map<String, Object> m = Json.parseObject(text);
        assertEquals(List.of("b", "a", "s"), List.copyOf(m.keySet()));
        assertEquals(1L, m.get("b"));
        assertEquals("{\"b\":1,\"a\":[true,false,null,2.5,-3,100],\"s\":\"x\\\"y\\né\"}", Json.write(m));
        assertEquals(Json.write(m), Json.write(Json.parse(Json.write(m))));
    }

    @Test
    void doublesAreRoundedHalfEvenAndNeverUseExponents() {
        assertEquals("0.333333", Json.formatDouble(1.0 / 3));
        assertEquals("0.000001", Json.formatDouble(0.0000005000001));
        assertEquals("12345678901.5", Json.formatDouble(12345678901.5));
        assertEquals("0", Json.formatDouble(-0.0));
        assertEquals("0", Json.formatDouble(1e-9));
        assertEquals("null", Json.formatDouble(Double.NaN));
        assertEquals("null", Json.formatDouble(Double.POSITIVE_INFINITY));
        assertEquals("2", Json.formatDouble(2.0));
    }

    @Test
    void writesControlCharactersEscaped() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "\u0001\t");
        assertEquals("{\"k\":\"\\u0001\\t\"}", Json.write(m));
    }

    @Test
    void rejectsMalformedInputAndDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("tru"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
    }

    @Test
    void sha256IsHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Json.sha256(""));
    }

    @Test
    void prettyOutputParsesToTheSameCanonicalForm() {
        Map<String, Object> m = Json.parseObject("{\"a\":{\"b\":[1,{\"c\":2}],\"d\":[]},\"e\":{}}");
        assertEquals(Json.write(m), Json.write(Json.parse(Json.writePretty(m))));
    }
}
