package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.service.OrderService.CreateOrderCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A key may replay its original checkout, but may never silently describe a different basket or address. */
@Component
public class OrderCreateRequestHasher {
    private final ObjectMapper json;

    public OrderCreateRequestHasher(ObjectMapper json) {
        this.json = json;
    }

    /** Sort nested object keys so equivalent map ordering does not create a false request conflict. */
    public String hash(CreateOrderCommand command) {
        String canonical = json.writeValueAsString(normalize(json.valueToTree(command)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private Object normalize(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), normalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(value -> result.add(normalize(value)));
            return result;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        return node.asString();
    }
}
