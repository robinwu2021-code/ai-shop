package ai.neargo.shop.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.junit.jupiter.api.Test;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Neutral fixtures prove wire behavior without depending on any industry module. */
class BoundInternalProtocolTest {
    record Request(
            String key, long revision, Map<String, String> values, List<BigDecimal> quantities) {}

    record Observation(Long revision, String hash, boolean canStart) {}

    record Result(Long money, long revision, Observation observation) {}

    private static final String TOKEN = "unit-only-internal-binding-secret";
    private static final TypeReference<InternalReply<Result>> REPLY = new TypeReference<>() {};
    private final JsonMapper json =
            JsonMapper.builder()
                    .changeDefaultPropertyInclusion(
                            v ->
                                    JsonInclude.Value.construct(
                                            JsonInclude.Include.NON_NULL,
                                            JsonInclude.Include.NON_NULL))
                    .build();

    private static InternalRequestBinding binding() {
        return new InternalRequestBinding(
                1,
                "BAPI-071",
                "SUBMIT_SERVICES",
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                "entity",
                "store",
                "work");
    }

    private static InternalCall<Request> call(
            Map<String, String> values, List<BigDecimal> quantities) {
        return new InternalCall<>(binding(), new Request("private-key", 1, values, quantities));
    }

    @Test
    void nonNullHostMapperStillEmitsNestedNullAndRejectsOmittedFields() {
        var reply =
                new InternalReply<>(
                        binding(),
                        "a".repeat(64),
                        new Result(null, 0, new Observation(null, null, false)));
        String encoded = json.writeValueAsString(reply);
        assertThat(encoded).contains("\"money\":null", "\"hash\":null", "\"canStart\":false");
        assertThat(json.readValue(encoded, REPLY)).isEqualTo(reply);
        ObjectNode tree = (ObjectNode) json.readTree(encoded);
        ((ObjectNode) tree.get("result")).remove("money");
        String missingNullable = tree.toString();
        assertThatThrownBy(() -> json.readValue(missingNullable, REPLY))
                .isInstanceOf(RuntimeException.class);
        tree = (ObjectNode) json.readTree(encoded);
        ((ObjectNode) tree.get("result")).remove("revision");
        String missingPrimitive = tree.toString();
        assertThatThrownBy(() -> json.readValue(missingPrimitive, REPLY))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void canonicalDigestCoversEveryTypedFieldWithoutExposingProof() {
        var bindings = new InternalBindings(TOKEN);
        var left = new LinkedHashMap<String, String>();
        left.put("b", "two");
        left.put("a", "one");
        var request = call(left, List.of(new BigDecimal("1.00"), new BigDecimal("2")));
        assertThatCode(() -> bindings.digest(request)).doesNotThrowAnyException();
        String digest = bindings.digest(request);
        assertThat(digest)
                .isEqualTo("96ec3e48f0aa5f1f9f0eec8c5a8dbb585ee81444e91181361e5ab2b4bac991e8");
        assertThat(
                        bindings.digest(
                                call(
                                        Map.of("a", "one", "b", "two"),
                                        List.of(BigDecimal.ONE, new BigDecimal("2.0")))))
                .isEqualTo(digest);
        assertThat(bindings.digest(call(left, List.of(new BigDecimal("2"), BigDecimal.ONE))))
                .isNotEqualTo(digest);
        assertThat(
                        bindings.digest(
                                new InternalCall<>(
                                        binding(),
                                        new Request(
                                                "changed-key",
                                                1,
                                                left,
                                                request.request().quantities()))))
                .isNotEqualTo(digest);
        assertThat(new InternalBindings("different-secret").digest(request)).isNotEqualTo(digest);
        assertThat(request.toString()).doesNotContain("private-key", "one");
    }

    @Test
    void completeBindingAndDigestAreRequiredBeforeAcceptingResult() {
        var bindings = new InternalBindings(TOKEN);
        var request = call(Map.of(), List.of(BigDecimal.ONE));
        var result = new Result(null, 0, new Observation(null, null, false));
        assertThatCode(
                        () ->
                                bindings.require(
                                        request,
                                        new InternalReply<>(
                                                binding(), bindings.digest(request), result)))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                bindings.require(
                                        request,
                                        new InternalReply<>(binding(), "b".repeat(64), result)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bindings.require(request, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalBindings("").digest(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyClientsKeepStatusSemanticsWhileBoundErrorsRequireDigest() {
        var legacy =
                InternalServiceResponses.invoke(
                        TOKEN,
                        TOKEN,
                        () -> {
                            throw BizException.of(ErrorCode.NOT_FOUND);
                        });
        assertThat(legacy.getStatusCode().value()).isEqualTo(404);
        assertThat(legacy.getHeaders().getFirst("X-Internal-Error-Code")).isNull();
        var bound =
                InternalServiceResponses.invokeBound(
                        TOKEN,
                        TOKEN,
                        () -> "c".repeat(64),
                        () -> {
                            throw BizException.of(ErrorCode.NOT_FOUND);
                        });
        assertThat(bound.getStatusCode().value()).isEqualTo(404);
        assertThat(bound.getHeaders().getFirst("X-Internal-Error-Code")).isEqualTo("NOT_FOUND");
        assertThat(bound.getHeaders().getFirst("X-Internal-Request-Digest"))
                .isEqualTo("c".repeat(64));
        assertThat(bound.getBody()).isNull();
        assertThat(
                        InternalServiceResponses.invokeBound(
                                        "",
                                        "",
                                        () -> {
                                            throw new AssertionError(
                                                    "authentication must precede digest");
                                        },
                                        () -> {
                                            throw new AssertionError(
                                                    "authentication must precede owner");
                                        })
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    @Test
    void requestDecoderRequiresExplicitNullableCoordinatesAndEveryTypedPayloadField() {
        var identity = new DelegatedIdentity("MAIN", "CONSUMER", "USR", "consumer");
        var request =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1, "BAPI-155", "GET_MY_WORK_ORDER", identity, null, null, "W"),
                        new Request("key", 0, Map.of(), List.of()));
        var type = new TypeReference<InternalCall<Request>>() {};
        String encoded = json.writeValueAsString(request);
        assertThat(json.readValue(encoded, type)).isEqualTo(request);
        ObjectNode tree = (ObjectNode) json.readTree(encoded);
        ((ObjectNode) tree.get("binding")).remove("storeNo");
        assertThatThrownBy(() -> json.readValue(tree.toString(), type))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void canonicalUnicodeAndControlEscapingIgnoresHostMapperSettings() {
        var request = call(Map.of("客户", "顾客\n\t\"\\姓名"), List.of(BigDecimal.ONE));
        var ascii = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
        var type = new TypeReference<InternalCall<Request>>() {};
        String ordinaryWire = json.writeValueAsString(request);
        String escapedWire = ascii.writeValueAsString(request);
        assertThat(escapedWire).isNotEqualTo(ordinaryWire);
        InternalCall<Request> ordinaryCall = json.readValue(ordinaryWire, type);
        InternalCall<Request> escapedCall = ascii.readValue(escapedWire, type);
        assertThat(escapedCall).isEqualTo(ordinaryCall);
        String ordinary = new InternalBindings(TOKEN).digest(ordinaryCall);
        assertThat(ordinary)
                .isEqualTo("d7957db3f73590df80ed84f9f93f8236c52c6fc815e0b3ecd1a95ea2c19f18dc");
        assertThat(new InternalBindings(TOKEN).digest(escapedCall)).isEqualTo(ordinary);
    }
}
