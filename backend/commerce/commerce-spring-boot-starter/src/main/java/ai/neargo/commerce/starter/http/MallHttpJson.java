package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.mall.MallException;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exact money applies only to the new mall wire contract; no global host mapper mutation. */
public final class MallHttpJson {
    private static final long JS_SAFE = 9007199254740991L;
    private final JsonMapper mapper =
            JsonMapper.builder()
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    public <T> T read(String raw, Class<T> type) {
        try {
            var tree = mapper.readTree(raw);
            normalize(tree, false);
            return mapper.treeToValue(tree, type);
        } catch (RuntimeException invalid) {
            throw new MallException("INVALID_REQUEST");
        }
    }

    public JsonNode view(Object value) {
        var tree = mapper.valueToTree(value);
        normalize(tree, true);
        return tree;
    }

    private static void normalize(JsonNode node, boolean output) {
        if (node == null) return;
        if (node.isObject() && node.has("currency") && node.has("minor")) {
            var amount = node.get("minor");
            var currency = node.get("currency");
            if (!currency.isString() || !currency.asString().matches("[A-Z]{3}"))
                throw new MallException("INVALID_MONEY");
            long exact;
            if (amount.isString()) {
                String raw = amount.asString();
                if (!raw.matches("0|[1-9][0-9]{0,18}")) throw new MallException("INVALID_MONEY");
                exact = Long.parseLong(raw);
            } else {
                if (!amount.isIntegralNumber() || !amount.canConvertToLong())
                    throw new MallException("INVALID_MONEY");
                exact = amount.longValue();
                if (exact < 0 || !output && exact > JS_SAFE)
                    throw new MallException("INVALID_MONEY");
            }
            if (output) ((ObjectNode) node).put("minor", Long.toString(exact));
            else ((ObjectNode) node).put("minor", exact);
            return;
        }
        if (node.isContainer()) for (var child : node) normalize(child, output);
    }
}
