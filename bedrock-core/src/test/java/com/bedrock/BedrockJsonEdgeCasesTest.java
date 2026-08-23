package com.bedrock;

import com.bedrock.core.BedrockJson;
import com.bedrock.exception.BedrockException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for BedrockJson Recursive Descent Parser.
 * 
 * These tests validate robustness against:
 * - Deep nesting attacks (DoS prevention)
 * - Number precision boundaries
 * - Unicode escape handling
 * - Malformed JSON detection
 * - POJO constructor requirements
 */
class BedrockJsonEdgeCasesTest {

    // --- NESTING DEPTH LIMITS ---

    @Test
    void shouldEnforceMaxNestingDepthForObjects() {
        // Build JSON with depth exceeding MAX_NESTING_DEPTH (128)
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 150; i++) {
            json.append("\"a\":{")
        }
        json.append("\"value\": 42");
        for (int i = 0; i < 150; i++) {
            json.append("}")
        }

        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json.toString(), Map.class),
            "Should reject JSON exceeding maximum nesting depth"
        );
    }

    @Test
    void shouldEnforceMaxNestingDepthForArrays() {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            json.append("[");
        }
        json.append("42");
        for (int i = 0; i < 150; i++) {
            json.append("]");
        }

        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json.toString(), List.class),
            "Should reject array nesting exceeding limit"
        );
    }

    @Test
    void shouldAllowValidNestingWithinLimit() {
        // Build valid JSON at depth boundary (128 levels)
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 100; i++) {
            json.append("\"level").append(i).append("\":{")
        }
        json.append("\"value\": 123");
        for (int i = 0; i < 100; i++) {
            json.append("}")
        }

        assertDoesNotThrow(
            () -> {
                Map<String, Object> result = BedrockJson.fromJson(json.toString(), Map.class);
                assertNotNull(result, "Should parse valid nested JSON within depth limit");
            }
        );
    }

    // --- NUMBER PRECISION BOUNDARIES ---

    @Test
    void shouldPreserveLongValuesAtMaxBoundary() {
        String json = "{\"id\":" + Long.MAX_VALUE + "}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        Object value = result.get("id");
        assertTrue(value instanceof Long, "Should preserve Long type at MAX_VALUE");
        assertEquals(Long.MAX_VALUE, value, "Should preserve exact Long.MAX_VALUE");
    }

    @Test
    void shouldPreserveLongValuesAtMinBoundary() {
        String json = "{\"id\":" + Long.MIN_VALUE + "}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        Object value = result.get("id");
        assertTrue(value instanceof Long, "Should preserve Long type at MIN_VALUE");
        assertEquals(Long.MIN_VALUE, value, "Should preserve exact Long.MIN_VALUE");
    }

    @Test
    void shouldDegradeToDoubleForNumbersBeyondLongRange() {
        // MAX_LONG + 1 is outside Long range
        String json = "{\"largeId\":9223372036854775808}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        Object value = result.get("largeId");
        assertTrue(
            value instanceof Double,
            "Should degrade to Double for numbers beyond Long range (note: precision loss)"
        );
    }

    @Test
    void shouldHandleScientificNotationCorrectly() {
        String json = "{\"large\":1.5e10,\"tiny\":1.5e-10,\"negative\":-2.5e5}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        assertEquals(1.5e10, (Double) result.get("large"), 0.01);
        assertEquals(1.5e-10, (Double) result.get("tiny"), 0.0e-20);
        assertEquals(-2.5e5, (Double) result.get("negative"), 0.01);
    }

    @Test
    void shouldHandleNegativeZero() {
        String json = "{\"negZeroInt\":-0,\"negZeroDouble\":-0.0}";
        assertDoesNotThrow(
            () -> {
                Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
                assertNotNull(result.get("negZeroInt"));
                assertNotNull(result.get("negZeroDouble"));
            }
        );
    }

    @Test
    void shouldHandleOverflowToInfinity() {
        String json = "{\"overflow\":1e309}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        Double value = (Double) result.get("overflow");
        assertTrue(
            Double.isInfinite(value),
            "Should parse overflow values as Infinity"
        );
    }

    // --- UNICODE ESCAPE HANDLING ---

    @Test
    void shouldParseValidUnicodeEscapes() {
        String json = "{\"greeting\":\"\\u0048\\u0065\\u006c\\u006c\\u006f\"}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        assertEquals("Hello", result.get("greeting"), "Should decode unicode escapes");
    }

    @Test
    void shouldHandleEmojiViaSurrogatePairs() {
        String json = "{\"emoji\":\"\\uD83D\\uDE00\"}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        assertEquals("😀", result.get("emoji"), "Should handle emoji surrogate pairs");
    }

    @Test
    void shouldRejectInvalidHexInUnicodeEscape() {
        String json = "{\"bad\":\"\\uXYZW\"}";
        
        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json, Map.class),
            "Should reject non-hex characters in unicode escape"
        );
    }

    @Test
    void shouldRejectTruncatedUnicodeEscape() {
        String json = "{\"bad\":\"\\u00\"}";
        
        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json, Map.class),
            "Should reject incomplete unicode escape sequence"
        );
    }

    // --- WHITESPACE AND EMPTY INPUT ---

    @Test
    void shouldHandleLeadingAndTrailingWhitespace() {
        String json = "   {\"key\": \"value\"}   \n\t";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        assertEquals("value", result.get("key"), "Should trim surrounding whitespace");
    }

    @Test
    void shouldHandleWhitespaceInObjectsAndArrays() {
        String json = "{ \n \"a\" : 1 , \n \"b\" : 2 \n }";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        assertEquals(1L, result.get("a"));
        assertEquals(2L, result.get("b"));
    }

    // --- MALFORMED JSON DETECTION ---

    @Test
    void shouldRejectTrailingCommaInObject() {
        String json = "{\"key\":\"value\",}";
        
        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json, Map.class),
            "Should reject trailing comma in object"
        );
    }

    @Test
    void shouldRejectTrailingCommaInArray() {
        String json = "[1,2,3,]";
        
        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json, List.class),
            "Should reject trailing comma in array"
        );
    }

    @Test
    void shouldHandleDuplicateKeysInObject() {
        String json = "{\"key\":\"first\",\"key\":\"second\"}";
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        
        // LinkedHashMap keeps insertion order; last value wins
        assertEquals("second", result.get("key"), "Should use last value for duplicate keys");
    }

    @Test
    void shouldRejectUnterminatedString() {
        String json = "{\"incomplete\": \"unclosed string}";
        
        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json, Map.class),
            "Should reject unterminated string literal"
        );
    }

    @Test
    void shouldRejectTrailingContent() {
        String json = "{\"valid\": true} extra junk";
        
        assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json, Map.class),
            "Should reject content after valid JSON"
        );
    }

    @Test
    void shouldRejectInvalidLiterals() {
        assertThrows(BedrockException.class, () -> BedrockJson.fromJson("{\"nan\": NaN}", Map.class));
        assertThrows(BedrockException.class, () -> BedrockJson.fromJson("{\"inf\": Infinity}", Map.class));
        assertThrows(BedrockException.class, () -> BedrockJson.fromJson("{\"und\": undefined}", Map.class));
    }

    // --- POJO DESERIALIZATION ---

    static class NoDefaultConstructor {
        private String value;

        public NoDefaultConstructor(String value) {
            this.value = value;
        }
    }

    @Test
    void shouldFailOnMissingNoArgConstructor() {
        String json = "{\"value\":\"test\"}";
        
        BedrockException ex = assertThrows(
            BedrockException.class,
            () -> BedrockJson.fromJson(json, NoDefaultConstructor.class),
            "Should fail when POJO lacks no-arg constructor"
        );
        
        assertTrue(
            ex.getMessage().contains("no-arg") || ex.getMessage().contains("no-argument"),
            "Error message should mention constructor requirement"
        );
    }

    // --- LARGE PAYLOADS ---

    @Test
    void shouldHandleLargeArray() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 10000; i++) {
            if (i > 0) json.append(",");
            json.append(i);
        }
        json.append("]");
        
        List<Object> result = BedrockJson.fromJson(json.toString(), List.class);
        assertEquals(10000, result.size(), "Should handle 10k-element array");
    }

    @Test
    void shouldHandleLargeString() {
        String largeContent = "x".repeat(100_000);
        String json = "{\"data\":\"" + largeContent + "\"}";
        
        Map<String, Object> result = BedrockJson.fromJson(json, Map.class);
        assertEquals(largeContent, result.get("data"), "Should handle 100k character string");
    }

    @Test
    void shouldHandleManyKeyValuePairs() {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) json.append(",");
            json.append("\"key").append(i).append("\":").append(i);
        }
        json.append("}");
        
        Map<String, Object> result = BedrockJson.fromJson(json.toString(), Map.class);
        assertEquals(5000, result.size(), "Should handle 5k key-value pairs");
    }

    // --- ROUND-TRIP CONSISTENCY ---

    @Test
    void shouldRoundTripWithoutDataLoss() {
        record TestData(String name, int value, boolean flag, List<Integer> items) {}
        
        TestData original = new TestData(
            "test",
            42,
            true,
            List.of(1, 2, 3)
        );
        
        String json = BedrockJson.toJson(original);
        TestData restored = BedrockJson.fromJson(json, TestData.class);
        
        assertEquals(original, restored, "Should round-trip without data loss");
    }

    @Test
    void shouldPreserveKeyOrderInJsonObject() {
        String json1 = "{\"z\":1,\"y\":2,\"x\":3}";
        String json2 = "{\"x\":3,\"y\":2,\"z\":1}";
        
        record Data(int x, int y, int z) {}
        
        Data d1 = BedrockJson.fromJson(json1, Data.class);
        Data d2 = BedrockJson.fromJson(json2, Data.class);
        
        assertEquals(d1, d2, "Key order should not affect deserialization");
    }
}
