package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** Canonical backend evidence digest, never a caller-authentication token or owner capability grant. */
public final class PricingSnapshotHash {
    private PricingSnapshotHash() { }

    public static String of(Object value) {
        String json = PricingJson.JSON.writeValueAsString(normalize(PricingJson.JSON.valueToTree(value)));
        if (json.getBytes(StandardCharsets.UTF_8).length > 2097152) {
            throw new IllegalArgumentException("Bounded source snapshot required");
        }
        return PricingJson.hash("PRICING_SOURCE_V1\n" + json);
    }

    private static Object normalize(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), normalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = new ArrayList<Object>();
            node.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (node.isNumber() && !node.isIntegralNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            return number.scale() < 0 ? number.setScale(0) : number;
        }
        return node;
    }
}
