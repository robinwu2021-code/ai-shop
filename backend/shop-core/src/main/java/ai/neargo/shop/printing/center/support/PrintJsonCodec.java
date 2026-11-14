package ai.neargo.shop.printing.center.support;

import ai.neargo.shop.print.api.*;

import org.springframework.stereotype.Component;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

@Component
public final class PrintJsonCodec {
    private final JsonMapper mapper =
            JsonMapper.builder(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(48)
                                                    .maxStringLength(3 * 1024 * 1024)
                                                    .maxNumberLength(100)
                                                    .build())
                                    .build())
                    .build();

    public FrozenJson freeze(String json) {
        if (json == null
                || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 3 * 1024 * 1024)
            throw new PrintFailure("print.payloadTooLarge");
        try {
            var n = mapper.readTree(json);
            if (n == null) throw new IllegalArgumentException();
            String text = mapper.writeValueAsString(sorted(n));
            return new FrozenJson(text, PrintHashes.sha256(text));
        } catch (Exception ex) {
            throw new PrintFailure("print.invalidJson");
        }
    }

    private JsonNode sorted(JsonNode n) {
        if (n.isObject()) {
            var out = mapper.createObjectNode();
            var names = new TreeSet<String>();
            n.propertyNames().forEach(names::add);
            for (var key : names) out.set(key, sorted(n.get(key)));
            return out;
        }
        if (n.isArray()) {
            var out = mapper.createArrayNode();
            n.forEach(v -> out.add(sorted(v)));
            return out;
        }
        return n;
    }

    public FrozenJson freeze(Object value) {
        return freeze(mapper.writeValueAsString(value));
    }

    public JsonNode tree(FrozenJson value) {
        verify(value);
        return mapper.readTree(value.canonicalJson());
    }

    public JsonNode tree(Object value) {
        return tree(freeze(value));
    }

    public JsonNode tree(String value) {
        return tree(freeze(value));
    }

    public <T> T read(String value, Class<T> type) {
        return mapper.readValue(value, type);
    }

    public String write(Object value) {
        return freeze(value).canonicalJson();
    }

    public void verify(FrozenJson json) {
        if (json == null || !freeze(json.canonicalJson()).equals(json))
            throw new PrintFailure("print.snapshotMismatch");
    }
}
