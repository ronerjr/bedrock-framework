package com.bedrock;

import com.bedrock.core.BedrockJson;
import com.bedrock.exception.BedrockException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockJsonTest {

    public record SimpleUser(String name, int age, boolean active) {}
    public record Address(String street, String city) {}
    public record UserWithAddress(String name, Address address) {}

    public static class PersonPojo {
        private String name;
        private int age;

        public PersonPojo() {}
        public PersonPojo(String name, int age) {
            this.name = name;
            this.age = age;
        }
        public String getName() { return name; }
        public int getAge() { return age; }
    }

    @Test
    void shouldSerializePrimitivesAndStrings() {
        assertEquals("\"hello\"", BedrockJson.toJson("hello"));
        assertEquals("123", BedrockJson.toJson(123));
        assertEquals("true", BedrockJson.toJson(true));
        assertEquals("null", BedrockJson.toJson(null));
    }

    @Test
    void shouldSerializeAndEscapeStrings() {
        String json = BedrockJson.toJson("Hello \"World\"\nNext line");
        assertEquals("\"Hello \\\"World\\\"\\nNext line\"", json);
    }

    @Test
    void shouldSerializeMapAndList() {
        Map<String, Object> map = Map.of("key", "value");
        String json = BedrockJson.toJson(map);
        assertEquals("{\"key\":\"value\"}", json);

        List<String> list = List.of("a", "b");
        assertEquals("[\"a\",\"b\"]", BedrockJson.toJson(list));
    }

    @Test
    void shouldSerializeJavaRecord() {
        SimpleUser user = new SimpleUser("Ada Lovelace", 36, true);
        String json = BedrockJson.toJson(user);
        assertTrue(json.contains("\"name\":\"Ada Lovelace\""));
        assertTrue(json.contains("\"age\":36"));
        assertTrue(json.contains("\"active\":true"));
    }

    @Test
    void shouldDeserializeSimpleRecord() {
        String json = """
        {
            "name": "Alan Turing",
            "age": 41,
            "active": false
        }
        """;

        SimpleUser user = BedrockJson.fromJson(json, SimpleUser.class);
        assertNotNull(user);
        assertEquals("Alan Turing", user.name());
        assertEquals(41, user.age());
        assertFalse(user.active());
    }

    @Test
    void shouldDeserializeNestedRecord() {
        String json = """
        {
            "name": "Grace Hopper",
            "address": {
                "street": "Arlington Blvd",
                "city": "Arlington"
            }
        }
        """;

        UserWithAddress user = BedrockJson.fromJson(json, UserWithAddress.class);
        assertNotNull(user);
        assertEquals("Grace Hopper", user.name());
        assertNotNull(user.address());
        assertEquals("Arlington Blvd", user.address().street());
        assertEquals("Arlington", user.address().city());
    }

    @Test
    void shouldDeserializePojo() {
        String json = "{\"name\":\"Linus Torvalds\",\"age\":54}";
        PersonPojo person = BedrockJson.fromJson(json, PersonPojo.class);
        assertNotNull(person);
        assertEquals("Linus Torvalds", person.getName());
        assertEquals(54, person.getAge());
    }

    @Test
    void shouldThrowBedrockExceptionOnMalformedJson() {
        String malformed = "{ \"name\": \"broken\" "; // Missing closing brace
        assertThrows(BedrockException.class, () -> BedrockJson.fromJson(malformed, SimpleUser.class));
    }
}
