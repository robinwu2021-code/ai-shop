package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol.Binding;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol.Call;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol.Reply;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.QuantityFacts;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceContinuity;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalServiceResponses;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure cases authored for the later authorized unified test phase; no business owner is simulated. */
class ServiceExecutionContractTest {
    private static final String HASH = "a".repeat(64);
    private static final String TOKEN = "test-only-original-secret";
    private static final ExecutionScope SCOPE = new ExecutionScope("MAIN", "entity", "store");
    private static final TypeReference<Call<ExecutionLookup>> CALL = new TypeReference<>() {};
    private static final TypeReference<Reply<QuantityFacts>> REPLY = new TypeReference<>() {};
    private final JsonMapper json = JsonMapper.builder().changeDefaultPropertyInclusion(v ->
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)).build();

    private static ExecutionBinding binding(String executionNo) {
        return new ExecutionBinding(SCOPE, "BEAUTY", "operation", "order", "child", "line", 1,
                "work", 1, "task", 0, "binding", 1, executionNo, 1, "submission", "fence", 1,
                1, 1, HASH, "definition", HASH, HASH, HASH);
    }

    private static Binding originalBinding(ExecutionBinding value) {
        return new Binding(1, "SERVICE_EXECUTION", "QUERY_ORIGINAL", value.scope(), value.executionNo(),
                ServiceExecutionContract.bindingHash(value));
    }

    @Test
    void rejectsNoncanonicalCoordinatesAndOwnerBounds() {
        assertThatThrownBy(() -> new ExecutionScope("OTHER", "entity", "store"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionScope("MAIN", " entity", "store"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionScope("MAIN", "x".repeat(33), "store"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding("x".repeat(33))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decimalOneIsExactWithoutRoundingOrOrdinalAllocation() {
        ServiceExecutionContract.requireTaskQuantity(new BigDecimal("1.000000"), "COUNT");
        for (String invalid : new String[] {"0", "0.5", "1.5", "2", "999"}) {
            assertThatThrownBy(() -> ServiceExecutionContract.requireTaskQuantity(
                    new BigDecimal(invalid), "COUNT")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ServiceExecutionContract.requireTaskQuantity(BigDecimal.ONE, "HOUR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityBucketsAreDisjointAndCannotOverflowOrFabricateCapacity() {
        assertThat(new QuantityFacts(3, 1, 1, 1).orderedUnits()).isEqualTo(3);
        for (int[] q : new int[][] {{0, 0, 0, 0}, {2, 1, 1, 1}, {999, Integer.MAX_VALUE, 1, 1}, {2, -1, 0, 0}}) {
            assertThatThrownBy(() -> new QuantityFacts(q[0], q[1], q[2], q[3]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void completeBindingHashChangesOnTargetWithoutSelfHashCycle() {
        ExecutionBinding first = binding("execution");
        assertThat(ServiceExecutionContract.bindingHash(first)).hasSize(64)
                .isNotEqualTo(ServiceExecutionContract.bindingHash(binding("other")));
        ExecutionBinding changed = new ExecutionBinding(first.scope(), first.caller(), first.sourceOperationNo(),
                first.orderNo(), first.subOrderNo(), first.sourceLineNo(), first.unitOrdinal(), first.workOrderNo(),
                first.scopeRevision(), first.taskNo(), first.taskRevision(), first.bindingNo(), first.bindingRevision(),
                first.executionNo(), first.attemptNo(), first.submissionNo(), first.sourceFenceNo(),
                first.sourceFenceRevision(), first.sourceEpoch(), first.sourceRevision(), first.sourceHash(),
                first.definitionVersionNo(), first.executionSnapshotHash(), first.planHash(), "b".repeat(64));
        assertThat(ServiceExecutionContract.bindingHash(changed))
                .isEqualTo(ServiceExecutionContract.bindingHash(first));
    }

    @Test
    void canonicalHashSortsKeysPreservesNullAndRejectsFractionalNumbers() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("z", null);
        left.put("a", 1);
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("a", 1L);
        right.put("z", null);
        assertThat(ServiceExecutionContract.canonical(left)).isEqualTo("{\"a\":1,\"z\":null}")
                .isEqualTo(ServiceExecutionContract.canonical(right));
        assertThatThrownBy(() -> ServiceExecutionContract.canonical(Map.of("a", new BigDecimal("1.5"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payloadRetainsPersonnelAndResourceCoordinates() {
        OperatorPersonnelFact person = new OperatorPersonnelFact("person", "assignment", 1, 2, 3,
                Instant.parse("2026-10-26T01:00:00Z"), null);
        String first = ServiceExecutionContract.payloadHash(binding("execution"), person, HASH, "resource", HASH);
        assertThat(first).isNotEqualTo(ServiceExecutionContract.payloadHash(
                binding("execution"), person, HASH, "other", HASH));
        assertThatThrownBy(() -> new OperatorPersonnelFact("person", "assignment", 1, 2, 3,
                Instant.parse("2026-10-26T01:00:00.000001Z"), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingContinuityIsSeparateFromDurableSuspensionAndNullableObservedProof() {
        assertThat(new SourceContinuity(2, 3, "PENDING", null, null, "SOURCE_UNKNOWN").state()).isEqualTo("PENDING");
        assertThatThrownBy(() -> new SourceContinuity(2, 3, "READY_UNCHANGED", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceContinuity(2, 3, "PENDING", 1L, null, "SOURCE_UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        ExecutionBinding value = binding("execution");
        SourceSuspension proof = new SourceSuspension("fence", "execution", ServiceExecutionContract.bindingHash(value),
                2, 4, 5, null, null, "SOURCE_SUSPENDED", HASH);
        assertThat(ServiceExecutionContract.suspensionHash(value, proof)).hasSize(64).isNotEqualTo(HASH);
        assertThatThrownBy(() -> ServiceExecutionContract.suspensionHash(binding("other"), proof))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionHashBindsRevisionNamespaceAndCompleteOriginalLookup() {
        ExecutionBinding value = binding("execution");
        StartIntentLookup lookup = new StartIntentLookup(value);
        String first = ServiceExecutionContract.actionHash(value, "PREPARE", "action", 0, HASH, lookup, null);
        assertThat(first).isNotEqualTo(ServiceExecutionContract.actionHash(
                value, "PREPARE", "action", 1, HASH, lookup, null));
        assertThatThrownBy(() -> ServiceExecutionContract.actionHash(value, "OBSERVE", "action", 1, HASH, lookup, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void originalEnvelopeRoundTripsOnlyWithExactTypedExplicitSchema() {
        ExecutionBinding value = binding("execution");
        Call<ExecutionLookup> call = new Call<>(originalBinding(value), new ExecutionLookup(value));
        assertThat(json.readValue(json.writeValueAsString(call), CALL)).isEqualTo(call);
        InternalBindings bindings = new InternalBindings(TOKEN);
        Reply<QuantityFacts> reply = bindings.reply(call, new QuantityFacts(1, 1, 0, 0));
        bindings.require(call, json.readValue(json.writeValueAsString(reply), REPLY));
        ObjectNode missing = (ObjectNode) json.readTree(json.writeValueAsString(call));
        ((ObjectNode) missing.get("request").get("binding")).remove("attemptNo");
        assertThatThrownBy(() -> json.readValue(missing.toString(), CALL)).isInstanceOf(RuntimeException.class);
        ObjectNode unknown = (ObjectNode) json.readTree(json.writeValueAsString(call));
        ((ObjectNode) unknown.get("binding")).put("actorNo", "saved-employee");
        assertThatThrownBy(() -> json.readValue(unknown.toString(), CALL)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue(json.writeValueAsString(call), Call.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void originalWireRejectsFractionalIntegralFieldsAndDuplicateKeys() {
        ExecutionBinding value = binding("execution");
        String encoded = json.writeValueAsString(new Call<>(originalBinding(value), new ExecutionLookup(value)));
        assertThatThrownBy(() -> json.readValue(encoded.replace("\"attemptNo\":1", "\"attemptNo\":1.5"), CALL))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue(encoded.replace("\"protocolVersion\":1",
                "\"protocolVersion\":1,\"protocolVersion\":1"), CALL)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void originalHmacRejectsTokenTargetReplayAndCopiedHumanDigest() {
        ExecutionBinding value = binding("execution");
        Call<ExecutionLookup> call = new Call<>(originalBinding(value), new ExecutionLookup(value));
        InternalBindings bindings = new InternalBindings(TOKEN);
        Reply<QuantityFacts> reply = bindings.reply(call, new QuantityFacts(1, 1, 0, 0));
        assertThatThrownBy(() -> new InternalBindings("wrong-token").require(call, reply))
                .isInstanceOf(IllegalArgumentException.class);
        ExecutionBinding other = binding("other");
        Call<ExecutionLookup> swapped = new Call<>(originalBinding(other), new ExecutionLookup(other));
        assertThatThrownBy(() -> bindings.require(swapped, reply)).isInstanceOf(IllegalArgumentException.class);
        InternalCall<ExecutionLookup> human = new InternalCall<>(new InternalRequestBinding(1, "SERVICE_EXECUTION",
                "QUERY", new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"), "entity", "store", "execution"),
                new ExecutionLookup(value));
        assertThat(bindings.digest(human)).isNotEqualTo(bindings.digest(call));
        assertThatThrownBy(() -> bindings.require(call,
                new Reply<>(call.binding(), bindings.digest(human), reply.result())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Binding(1, "SERVICE_EXECUTION", "QUERY", SCOPE, "execution", HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bindings.requireRoute(call,
                "SERVICE_EXECUTION_SOURCE", "INSPECT_ORIGINAL", "execution"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullableReplyPropertiesStayExplicitAndHumanOriginalWireSubstitutionFails() {
        ExecutionBinding value = binding("execution");
        Call<ExecutionLookup> call = new Call<>(originalBinding(value), new ExecutionLookup(value));
        var grant = new ServiceExecutionPort.Grant(value, "grant", 1,
                ServiceExecutionPort.GrantState.PREPARED, 0, new QuantityFacts(1, 1, 0, 0), null);
        String encoded = json.writeValueAsString(new InternalBindings(TOKEN).reply(call, grant));
        assertThat(encoded).contains("\"reasonCode\":null");
        ObjectNode missing = (ObjectNode) json.readTree(encoded);
        ((ObjectNode) missing.get("result")).remove("reasonCode");
        var type = new TypeReference<Reply<ServiceExecutionPort.Grant>>() {};
        assertThatThrownBy(() -> json.readValue(missing.toString(), type)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue(json.writeValueAsString(call),
                new TypeReference<InternalCall<ExecutionLookup>>() {})).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Call<>(new Binding(1, "SERVICE_EXECUTION", "QUERY_ORIGINAL",
                new ExecutionScope("MAIN", "other-entity", "store"), value.executionNo(),
                ServiceExecutionContract.bindingHash(value)), new ExecutionLookup(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void independentlyNullableObservedSuspensionFactsCannotTurnUnknownIntoWithdrawal() {
        ExecutionBinding value = binding("execution");
        String bindingHash = ServiceExecutionContract.bindingHash(value);
        assertThat(new SourceSuspension("fence", "execution", bindingHash, 2, 4, 5,
                2L, null, "SOURCE_SUSPENDED", HASH).observedSourceHash()).isNull();
        assertThat(new SourceSuspension("fence", "execution", bindingHash, 2, 4, 5,
                null, HASH, "SOURCE_SUSPENDED", HASH).observedSourceRevision()).isNull();
        for (String uncertain : new String[] {
                "SOURCE_UNKNOWN", "RESOURCE_EXECUTION_UNAVAILABLE", "EXECUTION_SCHEMA_UNAVAILABLE"}) {
            assertThatThrownBy(() -> new SourceSuspension("fence", "execution", bindingHash, 2, 4, 5,
                    null, null, uncertain, HASH)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SourceContinuity(4, 5, "SUSPENDED", null, null, uncertain))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void tokenCheckPrecedesDigestAndUnknownOriginalErrorsRemainDigestBound() {
        ExecutionBinding value = binding("execution");
        Call<ExecutionLookup> call = new Call<>(originalBinding(value), new ExecutionLookup(value));
        InternalBindings bindings = new InternalBindings(TOKEN);
        var unauthorized = InternalServiceResponses.invokeBound(TOKEN, "wrong", () -> {
            throw new AssertionError("digest must not execute before token verification");
        }, () -> {
            throw new AssertionError("operation must not execute before token verification");
        });
        assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
        var missing = InternalServiceResponses.invokeBound(TOKEN, TOKEN, () -> bindings.digest(call), () -> {
            throw BizException.of(ErrorCode.NOT_FOUND);
        });
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody()).isNull();
        assertThat(missing.getHeaders().getFirst("X-Internal-Error-Code")).isEqualTo("NOT_FOUND");
        assertThat(missing.getHeaders().getFirst("X-Internal-Request-Digest")).isEqualTo(bindings.digest(call));
    }

    @Test
    void originalDigestUsesExactPurposeDomainAndCompleteCanonicalWire() throws Exception {
        ExecutionBinding value = binding("execution");
        Call<ExecutionLookup> call = new Call<>(originalBinding(value), new ExecutionLookup(value));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = ("SHOP_EXECUTION_ORIGINAL_V1\n" + json.writeValueAsString(call))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(new InternalBindings(TOKEN).digest(call)).isEqualTo(HexFormat.of().formatHex(mac.doFinal(bytes)));
        assertThatThrownBy(() -> new InternalBindings("").digest(call)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistedBindingAndSuspensionEnvelopesContainExactDiscriminatorsAndOriginals() {
        ExecutionBinding value = binding("execution");
        var bindingTree = json.readTree(ServiceExecutionContract.bindingJson(value));
        assertThat(bindingTree.get("schema").asString()).isEqualTo("MANUAL_EXECUTION_V1");
        assertThat(bindingTree.get("binding").get("sourceHash").asString()).isEqualTo(HASH);
        SourceSuspension first = new SourceSuspension("fence", "execution",
                ServiceExecutionContract.bindingHash(value), 2, 4, 5, null, null, "SOURCE_SUSPENDED", HASH);
        String digest = ServiceExecutionContract.suspensionHash(value, first);
        SourceSuspension sealed = new SourceSuspension(first.fenceNo(), first.executionNo(), first.bindingHash(),
                first.intentRevision(), first.manualEpoch(), first.observationVersion(), null, null,
                first.reasonCode(), digest);
        var suspensionTree = json.readTree(ServiceExecutionContract.suspensionJson(value, sealed));
        assertThat(suspensionTree.get("schema").asString()).isEqualTo("BEAUTY_EXECUTION_SUSPEND_V1");
        assertThat(suspensionTree.get("suspension").get("observedSourceRevision").isNull()).isTrue();
        assertThatThrownBy(() -> ServiceExecutionContract.suspensionJson(value, first))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frozenIntentHashChangesWithOriginalContextAndIgnoresCurrentObservationState() {
        ExecutionBinding value = binding("execution");
        OperatorPersonnelFact person = new OperatorPersonnelFact("person", "assignment", 1, 2, 3,
                Instant.parse("2026-10-26T01:00:00Z"), null);
        var ready = new SourceContinuity(1, 1, "READY_UNCHANGED", 1L, HASH, null);
        var first = new ServiceExecutionSourcePort.StartIntent(value,
                ServiceExecutionSourcePort.IntentState.FROZEN, 1, "MANUAL_OFFLINE", HASH, person, HASH,
                "resource", HASH, "{\"schemaVersion\":1}", HASH, ready, null);
        var pending = new ServiceExecutionSourcePort.StartIntent(value,
                ServiceExecutionSourcePort.IntentState.EXECUTION_PENDING, 2, "MANUAL_OFFLINE", HASH, person, HASH,
                "resource", HASH, first.frozenPlan(), HASH,
                new SourceContinuity(2, 3, "PENDING", null, null, "SOURCE_UNKNOWN"), null);
        assertThat(ServiceExecutionContract.intentHash(first))
                .isEqualTo(ServiceExecutionContract.intentHash(pending));
        var changed = new ServiceExecutionSourcePort.StartIntent(value,
                ServiceExecutionSourcePort.IntentState.FROZEN, 1, "MANUAL_OFFLINE", "b".repeat(64), person, HASH,
                "resource", HASH, first.frozenPlan(), HASH, ready, null);
        assertThat(ServiceExecutionContract.intentHash(first))
                .isNotEqualTo(ServiceExecutionContract.intentHash(changed));
        assertThat(ServiceExecutionContract.bindingHash(value)).isNotEqualTo(ServiceExecutionContract.payloadHash(
                value, person, HASH, "resource", HASH));
    }

    record LegacyHumanFixture(String key, Long nullableFact, long revision) {}

    @Test
    void validLegacyHumanWireAndHmacRemainUnchanged() throws Exception {
        var fixture = new LegacyHumanFixture("private-key", null, 0);
        var binding = new InternalRequestBinding(1, "LEGACY_BOUND", "QUERY",
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"), "entity", "store", "target");
        var call = new InternalCall<>(binding, fixture);
        String encoded = json.writeValueAsString(call);
        assertThat(encoded).contains("\"nullableFact\":null", "\"revision\":0");
        assertThat(json.readValue(encoded, new TypeReference<InternalCall<LegacyHumanFixture>>() {}))
                .isEqualTo(call);
        InternalBindings bindings = new InternalBindings(TOKEN);
        var reply = bindings.reply(call, fixture);
        assertThat(json.readValue(json.writeValueAsString(reply),
                new TypeReference<InternalReply<LegacyHumanFixture>>() {})).isEqualTo(reply);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = ("SHOP_BOUND_INTERNAL_V1\n" + encoded).getBytes(StandardCharsets.UTF_8);
        assertThat(bindings.digest(call)).isEqualTo(HexFormat.of().formatHex(mac.doFinal(bytes)));
    }

    @Test
    void humanCallAndReplyRejectTopLevelAndNestedDuplicateKeys() {
        var fixture = new LegacyHumanFixture("private-key", null, 0);
        var binding = new InternalRequestBinding(1, "LEGACY_BOUND", "QUERY",
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"), "entity", "store", "target");
        var call = new InternalCall<>(binding, fixture);
        String encoded = json.writeValueAsString(call);
        var callType = new TypeReference<InternalCall<LegacyHumanFixture>>() {};
        String duplicateTop = encoded.replace("\"request\":",
                "\"request\":" + json.writeValueAsString(fixture) + ",\"request\":");
        assertThatThrownBy(() -> json.readValue(duplicateTop, callType)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue(encoded.replace("\"revision\":0",
                "\"revision\":0,\"revision\":0"), callType)).isInstanceOf(RuntimeException.class);
        var reply = new InternalBindings(TOKEN).reply(call, fixture);
        String replyJson = json.writeValueAsString(reply);
        var replyType = new TypeReference<InternalReply<LegacyHumanFixture>>() {};
        String duplicateDigest = replyJson.replace("\"requestDigest\":",
                "\"requestDigest\":\"" + reply.requestDigest() + "\",\"requestDigest\":");
        assertThatThrownBy(() -> json.readValue(duplicateDigest, replyType)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue(replyJson.replace("\"protocolVersion\":1",
                "\"protocolVersion\":1,\"protocolVersion\":1"), replyType)).isInstanceOf(RuntimeException.class);
    }
}
