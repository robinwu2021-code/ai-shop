package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.*;

import java.nio.file.*;

/** Compare documented examples with MVC output, including nullable fields and nested wire types. */
final class FoundationHttpExamples {
    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode example(String name) throws Exception {
        var file = Path.of("../../docs/api/examples/b-foundation.json");
        return JSON.readTree(Files.readString(file)).get(name);
    }

    static void data(String name, JsonNode actual) throws Exception {
        var response = example(name).get("response");
        assertEquals(0, response.get("code").asInt());
        assertEquals("success", response.get("msg").asText());
        shape(response.get("data"), actual, name);
    }

    private static void shape(JsonNode expected, JsonNode actual, String path) {
        assertNotNull(actual, path);
        assertEquals(expected.getNodeType(), actual.getNodeType(), path);
        if (expected.isObject()) {
            assertEquals(expected.propertyNames(), actual.propertyNames(), path);
            for (String key : expected.propertyNames())
                shape(expected.get(key), actual.get(key), path + "." + key);
        } else if (expected.isArray()) {
            assertEquals(expected.size(), actual.size(), path);
            for (int i = 0; i < expected.size(); i++)
                shape(expected.get(i), actual.get(i), path + "[" + i + "]");
        } else if (expected.isNumber()) {
            assertEquals(expected.isIntegralNumber(), actual.isIntegralNumber(), path);
        }
    }
}
