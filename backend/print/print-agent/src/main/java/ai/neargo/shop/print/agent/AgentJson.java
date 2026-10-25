package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.api.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

public final class AgentJson {
    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String value, Class<T> type) {
        return mapper.readValue(value, type);
    }

    public JsonNode tree(String value) {
        return mapper.readTree(value);
    }

    public FrozenJson freeze(Object value) {
        String s = mapper.writeValueAsString(sorted(mapper.valueToTree(value)));
        return new FrozenJson(s, PrintHashes.sha256(s));
    }

    private JsonNode sorted(JsonNode n) {
        if (n.isObject()) {
            var out = mapper.createObjectNode();
            var keys = new TreeSet<String>();
            n.propertyNames().forEach(keys::add);
            for (var key : keys) out.set(key, sorted(n.get(key)));
            return out;
        }
        if (n.isArray()) {
            var out = mapper.createArrayNode();
            n.forEach(v -> out.add(sorted(v)));
            return out;
        }
        return n;
    }
}
