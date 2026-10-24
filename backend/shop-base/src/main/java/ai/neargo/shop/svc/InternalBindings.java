package ai.neargo.shop.svc;

import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Binds the complete typed request while keeping proofs, keys and contact data private. */
public final class InternalBindings {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final String token;
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder().build();

    /** Disabled deployments may construct this helper; only actual digest use needs a secret. */
    public InternalBindings(String token) {
        this.token = token;
    }

    /** HMAC prevents echoed request metadata from becoming a contact-guessing oracle. */
    public String digest(InternalCall<?> call) {
        if (token == null || token.isBlank() || call == null) {
            throw invalid();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = "SHOP_BOUND_INTERNAL_V1\n" + canonical(tree(call), CANONICAL_JSON);
            return HexFormat.of()
                    .formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("internal binding unavailable");
        }
    }

    /** A changed coordinate or payload must fail before consumers accept any result. */
    public void require(InternalCall<?> call, InternalReply<?> reply) {
        if (call == null
                || reply == null
                || reply.result() == null
                || !call.binding().equals(reply.binding())
                || !equal(digest(call), reply.requestDigest())) {
            throw invalid();
        }
    }

    /** Route constants, never supplied action text, select the business operation. */
    public void requireRoute(
            InternalCall<?> call,
            String endpoint,
            String action,
            String realmMode,
            String targetNo) {
        if (call == null
                || !endpoint.equals(call.binding().endpoint())
                || !action.equals(call.binding().action())
                || !realmMode.equals(call.binding().identity().realm())
                || !Objects.equals(targetNo, call.binding().targetNo())) {
            throw invalid();
        }
    }

    /** Nested owner scopes must be the same namespace authenticated by the outer envelope. */
    public void requireCoordinates(
            InternalCall<?> call, String tenant, String entity, String store, String actor) {
        if (call == null
                || !Objects.equals(tenant, call.binding().identity().tenantNo())
                || !Objects.equals(entity, call.binding().entityNo())
                || !Objects.equals(store, call.binding().storeNo())
                || !Objects.equals(actor, call.binding().identity().actorNo())) {
            throw invalid();
        }
    }

    /** Recompute from the server's own decoded request; never copy an unverified client digest. */
    public <T> InternalReply<T> reply(InternalCall<?> call, T result) {
        return new InternalReply<>(call.binding(), digest(call), result);
    }

    private static boolean equal(String expected, String actual) {
        return actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8));
    }

    /** Build explicit null nodes so local mapper inclusion settings cannot alter bound schemas. */
    static JsonNode tree(Object value) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof String text) {
            return NODES.stringNode(text);
        }
        if (value instanceof Boolean flag) {
            return NODES.booleanNode(flag);
        }
        if (value instanceof BigDecimal number) {
            return NODES.numberNode(number.stripTrailingZeros());
        }
        if (value instanceof BigInteger number) {
            return NODES.numberNode(number);
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return NODES.numberNode(((Number) value).longValue());
        }
        if (value instanceof Instant instant) {
            return NODES.stringNode(instant.toString());
        }
        if (value instanceof OffsetDateTime time) {
            return NODES.stringNode(time.toInstant().toString());
        }
        if (value instanceof LocalDate date) {
            return NODES.stringNode(date.toString());
        }
        if (value instanceof List<?> items) {
            var array = NODES.arrayNode();
            items.forEach(item -> array.add(tree(item)));
            return array;
        }
        Map<String, Object> fields = new TreeMap<>();
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw invalid();
                }
                fields.put(key, entry.getValue());
            }
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    var accessor = component.getAccessor();
                    if (!accessor.canAccess(value) && !accessor.trySetAccessible()) {
                        throw invalid();
                    }
                    fields.put(component.getName(), accessor.invoke(value));
                } catch (IllegalAccessException | InvocationTargetException failure) {
                    throw invalid();
                }
            }
        } else {
            throw invalid();
        }
        var object = NODES.objectNode();
        fields.forEach((key, item) -> object.set(key, tree(item)));
        return object;
    }

    private static String canonical(JsonNode value, ObjectMapper json) {
        if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (value.isArray()) {
            var items = new ArrayList<String>();
            value.forEach(item -> items.add(canonical(item, json)));
            return "[" + String.join(",", items) + "]";
        }
        if (value.isObject()) {
            var items = new ArrayList<String>();
            value.properties()
                    .forEach(
                            entry ->
                                    items.add(
                                            json.writeValueAsString(entry.getKey())
                                                    + ":"
                                                    + canonical(entry.getValue(), json)));
            return "{" + String.join(",", items) + "}";
        }
        return json.writeValueAsString(value);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid bound internal message");
    }
}
