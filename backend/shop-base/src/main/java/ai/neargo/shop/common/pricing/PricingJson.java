package ai.neargo.shop.common.pricing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure, bounded string decoding; no runtime class lookup, external resource, or shared mutable JSON tree. */
final class PricingJson {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(256).maxNameLength(64).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    private PricingJson() {
    }

    static JsonNode parse(String text) {
        if (text == null || text.length() > 65536 || text.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw invalid("JSON input exceeds the bounded configuration size");
        }
        JsonNode node = JSON.readTree(text);
        if (node == null || node.isMissingNode()) {
            throw invalid("JSON input is required");
        }
        return node;
    }

    static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (!node.isObject()) {
            throw invalid("A configuration object is required");
        }
        node.properties().forEach(entry -> {
            if (!allowed.contains(entry.getKey())) {
                throw invalid("Unknown configuration field");
            }
        });
        for (String field : required) {
            if (!node.has(field)) {
                throw invalid("A required configuration field is missing");
            }
        }
    }

    static String canonical(JsonNode node) {
        return JSON.writeValueAsString(normalize(node));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static Object normalize(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, Object>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), normalize(entry.getValue())));
            return fields;
        }
        if (node.isArray()) {
            var values = new ArrayList<Object>();
            node.forEach(value -> values.add(normalize(value)));
            return values;
        }
        return node;
    }
}
