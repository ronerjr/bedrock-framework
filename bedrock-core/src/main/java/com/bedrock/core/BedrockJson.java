package com.bedrock.core;

import com.bedrock.exception.BedrockException;

import java.lang.reflect.*;
import java.util.*;

/**
 * Educational, zero-dependency JSON serialization and deserialization engine for Bedrock Java.
 * 
 * ALGORITHMIC STRATEGY:
 * 1. Deserialization: Uses a Recursive Descent Parser (Lexer + Parser) to parse JSON text
 *    into generic in-memory AST nodes (Maps, Lists, Primitives), and then leverages Java Reflection
 *    and Java 21 Record Components to materialize strongly-typed objects.
 * 2. Serialization: Uses Reflection to inspect Records, POJOs, Maps, and Iterables to produce
 *    valid JSON strings.
 */
public final class BedrockJson {

    private BedrockJson() {
        // Utility class
    }

    // ==========================================
    // SERIALIZATION (Java Object -> JSON String)
    // ==========================================

    /**
     * Serializes any Java object (Record, POJO, Map, List, Primitive) to a JSON string.
     */
    public static String toJson(Object obj) {
        if (obj == null) return "null";

        if (obj instanceof String str) {
            return "\"" + escapeJsonString(str) + "\"";
        }

        if (obj instanceof Character ch) {
            return "\"" + escapeJsonString(ch.toString()) + "\"";
        }

        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }

        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJsonString(String.valueOf(entry.getKey()))).append("\":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }

        if (obj instanceof Iterable<?> iter) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : iter) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            return sb.append("]").toString();
        }

        if (obj.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int length = Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(Array.get(obj, i)));
            }
            return sb.append("]").toString();
        }

        Class<?> clazz = obj.getClass();

        // 🚀 Native support for Java 21 Records
        if (clazz.isRecord()) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (RecordComponent component : clazz.getRecordComponents()) {
                if (!first) sb.append(",");
                try {
                    Object val = component.getAccessor().invoke(obj);
                    sb.append("\"").append(component.getName()).append("\":").append(toJson(val));
                } catch (Exception e) {
                    throw new BedrockException(
                        "Failed to serialize Record '" + clazz.getSimpleName() + "' component '" + component.getName() + "'.",
                        "Ensure the record component accessor is accessible.",
                        e
                    );
                }
                first = false;
            }
            return sb.append("}").toString();
        }

        // Support for classic POJOs (regular classes)
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            if (!first) sb.append(",");
            try {
                Object val = field.get(obj);
                sb.append("\"").append(field.getName()).append("\":").append(toJson(val));
            } catch (Exception e) {
                throw new BedrockException(
                    "Failed to serialize POJO '" + clazz.getSimpleName() + "' field '" + field.getName() + "'.",
                    "Check reflection access permissions.",
                    e
                );
            }
            first = false;
        }
        return sb.append("}").toString();
    }

    private static String escapeJsonString(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        String hex = "000" + Integer.toHexString(c);
                        sb.append("\\u").append(hex.substring(hex.length() - 4));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ============================================
    // DESERIALIZATION (JSON String -> Java Object)
    // ============================================

    /**
     * Parses a JSON string into a strongly-typed Java object (Record, POJO, or Primitive).
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Class<T> targetClass) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        Object parsedNode = new JsonReader(json.trim()).parse();
        return (T) mapNodeToObject(parsedNode, targetClass);
    }

    @SuppressWarnings("unchecked")
    private static Object mapNodeToObject(Object node, Class<?> targetClass) {
        if (node == null) {
            return null;
        }

        if (targetClass.equals(Object.class)) {
            return node;
        }

        // Direct primitive / wrapper types
        if (targetClass.equals(String.class)) {
            return String.valueOf(node);
        }
        if (targetClass.equals(int.class) || targetClass.equals(Integer.class)) {
            return ((Number) node).intValue();
        }
        if (targetClass.equals(long.class) || targetClass.equals(Long.class)) {
            return ((Number) node).longValue();
        }
        if (targetClass.equals(double.class) || targetClass.equals(Double.class)) {
            return ((Number) node).doubleValue();
        }
        if (targetClass.equals(float.class) || targetClass.equals(Float.class)) {
            return ((Number) node).floatValue();
        }
        if (targetClass.equals(boolean.class) || targetClass.equals(Boolean.class)) {
            if (node instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(node));
        }

        if (targetClass.equals(Map.class)) {
            return node;
        }

        if (targetClass.equals(List.class) && node instanceof List<?> list) {
            return list;
        }

        // 🚀 Java 21 Record Deserialization via Canonical Constructor
        if (targetClass.isRecord()) {
            if (!(node instanceof Map<?, ?> map)) {
                throw new BedrockException(
                    "Cannot map JSON node of type " + node.getClass().getSimpleName() + " to Record " + targetClass.getSimpleName(),
                    "Ensure JSON payload matches the Record object format (e.g., { ... })."
                );
            }
            RecordComponent[] components = targetClass.getRecordComponents();
            Class<?>[] paramTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];

            for (int i = 0; i < components.length; i++) {
                RecordComponent comp = components[i];
                paramTypes[i] = comp.getType();
                Object rawVal = map.get(comp.getName());
                args[i] = mapNodeToObject(rawVal, comp.getType());
            }

            try {
                Constructor<?> canonicalConstructor = targetClass.getDeclaredConstructor(paramTypes);
                canonicalConstructor.setAccessible(true);
                return canonicalConstructor.newInstance(args);
            } catch (Exception e) {
                throw new BedrockException(
                    "Failed to instantiate Record '" + targetClass.getSimpleName() + "' via reflection.",
                    "Ensure the record canonical constructor is valid and parameter types match JSON fields.",
                    e
                );
            }
        }

        // Classic POJO Deserialization
        if (node instanceof Map<?, ?> map) {
            try {
                Constructor<?> noArgConstructor = targetClass.getDeclaredConstructor();
                noArgConstructor.setAccessible(true);
                Object instance = noArgConstructor.newInstance();

                for (Field field : targetClass.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                        continue;
                    }
                    if (map.containsKey(field.getName())) {
                        field.setAccessible(true);
                        Object rawVal = map.get(field.getName());
                        Object mappedVal = mapNodeToObject(rawVal, field.getType());
                        field.set(instance, mappedVal);
                    }
                }
                return instance;
            } catch (Exception e) {
                throw new BedrockException(
                    "Failed to deserialize POJO '" + targetClass.getSimpleName() + "'.",
                    "Ensure '" + targetClass.getSimpleName() + "' has a default no-argument constructor.",
                    e
                );
            }
        }

        throw new BedrockException(
            "Unsupported deserialization target class: " + targetClass.getName(),
            "Bedrock JSON parser supports Java Records, POJOs with no-arg constructors, Maps, and standard types."
        );
    }

    // ============================================
    // RECURSIVE DESCENT JSON READER / PARSER
    // ============================================

    private static class JsonReader {
        private final String src;
        private int pos = 0;

        public JsonReader(String src) {
            this.src = src;
        }

        public Object parse() {
            skipWhitespace();
            Object result = parseValue();
            skipWhitespace();
            if (pos < src.length()) {
                throw new BedrockException(
                    "Malformed JSON: unexpected characters trailing after JSON payload at position " + pos,
                    "Verify your JSON payload syntax."
                );
            }
            return result;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new BedrockException("Malformed JSON: unexpected end of input", "Check if JSON payload is complete.");
            }
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();

            throw new BedrockException(
                "Malformed JSON: unexpected character '" + c + "' at position " + pos,
                "Check JSON syntax."
            );
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            consume('{');
            skipWhitespace();

            if (peek() == '}') {
                consume('}');
                return map;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                consume(':');
                Object val = parseValue();
                map.put(key, val);

                skipWhitespace();
                char next = peek();
                if (next == '}') {
                    consume('}');
                    break;
                } else if (next == ',') {
                    consume(',');
                } else {
                    throw new BedrockException(
                        "Malformed JSON: expected ',' or '}' inside object at position " + pos + " but found '" + next + "'",
                        "Ensure JSON object fields are comma-separated."
                    );
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            consume('[');
            skipWhitespace();

            if (peek() == ']') {
                consume(']');
                return list;
            }

            while (true) {
                Object val = parseValue();
                list.add(val);
                skipWhitespace();
                char next = peek();
                if (next == ']') {
                    consume(']');
                    break;
                } else if (next == ',') {
                    consume(',');
                } else {
                    throw new BedrockException(
                        "Malformed JSON: expected ',' or ']' inside array at position " + pos + " but found '" + next + "'",
                        "Ensure array elements are comma-separated."
                    );
                }
            }
            return list;
        }

        private String parseString() {
            consume('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new BedrockException("Malformed JSON: unfinished escape sequence", "Check escaping in string.");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new BedrockException("Malformed JSON: invalid unicode escape", "Check unicode values.");
                            }
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new BedrockException("Malformed JSON: unclosed string literal", "Ensure all JSON strings are closed with \".");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean isDecimal = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = src.substring(start, pos);
            if (isDecimal) {
                return Double.parseDouble(numStr);
            }
            try {
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                return Double.parseDouble(numStr);
            }
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new BedrockException("Malformed JSON: invalid boolean literal at position " + pos, "Use 'true' or 'false'.");
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new BedrockException("Malformed JSON: invalid null literal at position " + pos, "Use 'null'.");
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= src.length()) return '\0';
            return src.charAt(pos);
        }

        private void consume(char expected) {
            if (pos >= src.length() || src.charAt(pos) != expected) {
                throw new BedrockException(
                    "Malformed JSON: expected '" + expected + "' at position " + pos + " but found '" + (pos < src.length() ? src.charAt(pos) : "EOF") + "'",
                    "Check JSON structure."
                );
            }
            pos++;
        }
    }
}
