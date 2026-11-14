package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Reason;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure encoding/shape rules keep embedded and remote owners on the same original proof bytes. */
public final class ServiceExecutionContract {
    public static final String SCHEMA = "MANUAL_EXECUTION_V1";
    public static final int QUANTITY = 1;
    public static final String UOM = "COUNT";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ServiceExecutionContract() {}

    /** Hash every original coordinate except payloadHash so the payload cannot hash itself. */
    public static String bindingHash(ExecutionBinding binding) {
        return sha256(SCHEMA, bindingPayload(binding));
    }

    /** Original actor/person revisions and resource command belong to the frozen payload, not current access. */
    public static String payloadHash(ExecutionBinding binding, OperatorPersonnelFact personnel,
            String operatorNamespaceHash, String resourceCommandNo, String resourceCommandHash) {
        Objects.requireNonNull(personnel, "personnel");
        hash(operatorNamespaceHash);
        reference(resourceCommandNo, 32);
        hash(resourceCommandHash);
        Map<String, Object> payload = new TreeMap<>();
        payload.put("binding", bindingPayload(binding));
        payload.put("operatorNamespaceHash", operatorNamespaceHash);
        payload.put("personnel", personnel);
        payload.put("quantity", QUANTITY);
        payload.put("resourceCommandHash", resourceCommandHash);
        payload.put("resourceCommandNo", resourceCommandNo);
        payload.put("schema", SCHEMA);
        payload.put("uom", UOM);
        return sha256(SCHEMA, payload);
    }

    /** Frozen intent hashing excludes current state, observation, revision and first-suspension projections. */
    public static String intentHash(StartIntent intent) {
        Objects.requireNonNull(intent, "intent");
        Map<String, Object> payload = recordFields(intent);
        for (String mutable : List.of("state", "intentRevision", "continuity", "suspension")) {
            payload.remove(mutable);
        }
        return sha256("BEAUTY_EXECUTION_INTENT_V1", payload);
    }

    /** The persisted envelope owns its discriminator without adding a field to immutable binding coordinates. */
    public static String bindingJson(ExecutionBinding binding) {
        Objects.requireNonNull(binding, "binding");
        return canonical(Map.of("schema", SCHEMA, "binding", binding));
    }

    /** Action evidence keeps the complete original and rejects a mismatched self-excluded suspension digest. */
    public static String suspensionJson(ExecutionBinding binding, SourceSuspension suspension) {
        if (!suspensionHash(binding, suspension).equals(suspension.proofHash())) {
            throw invalid();
        }
        return canonical(Map.of("schema", "BEAUTY_EXECUTION_SUSPEND_V1",
                "binding", binding, "suspension", suspension));
    }

    /** An action covers the complete original lookup and optional durable contradiction, never mutable grant state. */
    public static String actionHash(ExecutionBinding binding, String kind, String actionNo,
            long expectedGrantRevision, String actorNamespaceHash, StartIntentLookup proof,
            SourceSuspension suspension) {
        if (kind == null || !Set.of("PREPARE", "SOURCE_SUSPEND", "OBSERVE").contains(kind)) {
            throw invalid();
        }
        reference(actionNo, 32);
        nonnegative(expectedGrantRevision);
        Objects.requireNonNull(proof, "proof");
        if (!binding.equals(proof.binding()) || "OBSERVE".equals(kind) && actorNamespaceHash != null
                || "PREPARE".equals(kind) && actorNamespaceHash == null
                || "SOURCE_SUSPEND".equals(kind) != (suspension != null)) {
            throw invalid();
        }
        if (actorNamespaceHash != null) {
            hash(actorNamespaceHash);
        }
        if (suspension != null) {
            requireSuspensionBinding(binding, suspension);
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("actionKind", kind);
        payload.put("actionNo", actionNo);
        payload.put("actorNamespaceHash", actorNamespaceHash);
        payload.put("bindingHash", bindingHash(binding));
        payload.put("expectedGrantRevision", expectedGrantRevision);
        payload.put("proof", proof);
        payload.put("suspension", suspension);
        return sha256("MANUAL_EXECUTION_ACTION_V1", payload);
    }

    /** First suspension retains both complete immutable binding and distinct current cause epochs. */
    public static String suspensionHash(ExecutionBinding binding, SourceSuspension suspension) {
        requireSuspensionBinding(binding, suspension);
        Map<String, Object> payload = recordFields(suspension);
        payload.remove("proofHash");
        payload.put("binding", binding);
        return sha256("BEAUTY_EXECUTION_SUSPEND_V1", payload);
    }

    /** Shape association is checked without treating the proof as current permission. */
    public static void requireSuspensionBinding(ExecutionBinding binding, SourceSuspension suspension) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(suspension, "suspension");
        if (!binding.sourceFenceNo().equals(suspension.fenceNo())
                || !binding.executionNo().equals(suspension.executionNo())
                || !bindingHash(binding).equals(suspension.bindingHash())) {
            throw invalid();
        }
    }

    /** Decimal one is accepted exactly; callers must never round or split an existing task. */
    public static void requireTaskQuantity(BigDecimal quantity, String uom) {
        if (quantity == null || quantity.compareTo(BigDecimal.ONE) != 0 || !UOM.equals(uom)) {
            throw new IllegalArgumentException("UNSUPPORTED_TASK_QUANTITY");
        }
    }

    /** Canonical JSON has sorted keys, explicit nulls and exact integer numbers; list order is retained. */
    public static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return JSON.writeValueAsString(text);
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.toBigIntegerExact().toString();
            } catch (ArithmeticException failure) {
                throw invalid();
            }
        }
        if (value instanceof Instant instant) {
            if (!instant.equals(instant.truncatedTo(ChronoUnit.MILLIS))) {
                throw invalid();
            }
            return canonical(instant.toString());
        }
        if (value instanceof Enum<?> constant) {
            return canonical(constant.name());
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(item -> parts.add(canonical(item)));
            return "[" + String.join(",", parts) + "]";
        }
        Map<String, Object> fields;
        if (value instanceof Map<?, ?> map) {
            fields = new TreeMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw invalid();
                }
                fields.put(text, item);
            });
        } else if (value.getClass().isRecord()) {
            fields = recordFields(value);
        } else {
            throw invalid();
        }
        List<String> parts = new ArrayList<>();
        fields.forEach((key, item) -> parts.add(canonical(key) + ":" + canonical(item)));
        return "{" + String.join(",", parts) + "}";
    }

    static void reference(String value, int bound) {
        if (value == null || value.isBlank() || value.length() > bound || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
    }

    static void exact(String value, String expected) {
        if (!expected.equals(value)) {
            throw invalid();
        }
    }

    static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
    }

    static void positive(long value) {
        if (value <= 0) {
            throw invalid();
        }
    }

    static void nonnegative(long value) {
        if (value < 0) {
            throw invalid();
        }
    }

    static void reason(String value, boolean required) {
        if (value == null && !required) {
            return;
        }
        if (value == null) {
            throw invalid();
        }
        try {
            Reason.valueOf(value);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    static void suspensionReason(String value) {
        reason(value, true);
        if (Set.of("SOURCE_UNKNOWN", "RESOURCE_EXECUTION_UNAVAILABLE", "EXECUTION_SCHEMA_UNAVAILABLE")
                .contains(value)) {
            throw new IllegalArgumentException("uncertain dependency observation is not durable suspension proof");
        }
    }

    static void observed(Long revision, String digest) {
        if ((revision == null) != (digest == null)) {
            throw invalid();
        }
        observedNullable(revision, digest);
    }

    static void observedNullable(Long revision, String digest) {
        if (revision != null) {
            nonnegative(revision);
        }
        if (digest != null) {
            hash(digest);
        }
    }

    static void objectJson(String value, int bound) {
        if (value == null || value.isBlank() || value.length() > bound) {
            throw invalid();
        }
        try {
            if (!JSON.readTree(value).isObject()) {
                throw invalid();
            }
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static Map<String, Object> bindingPayload(ExecutionBinding binding) {
        Objects.requireNonNull(binding, "binding");
        Map<String, Object> payload = recordFields(binding);
        payload.remove("payloadHash");
        return payload;
    }

    private static Map<String, Object> recordFields(Object value) {
        Map<String, Object> fields = new TreeMap<>();
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
        return fields;
    }

    private static String sha256(String domain, Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((domain + "\n" + canonical(payload)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid manual execution proof shape");
    }
}
