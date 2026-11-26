package ai.neargo.shop.merchant.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Interval;
import ai.neargo.shop.merchant.service.ResourceAdmissionService.Qualification;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.AbstractList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Real strict contract/codec tests; no source state or permission is simulated as business acceptance. */
class ResourceSourceContractTest {
    private static final String CODEC = "ai.neargo.shop.merchant.policy.ResourcePlanCodec";
    private static final String PORT = "ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort";
    private static final Instant START = Instant.parse("2026-10-21T10:00:00Z");
    private static final String JSON = "{\"schemaVersion\":1,\"demands\":[{\"resourceNo\":\"R\","
            + "\"sourceLineRef\":\"L\",\"quantity\":1,\"intervals\":[{\"startAt\":\"2026-10-21T10:00:00Z\","
            + "\"endAt\":\"2026-10-21T10:05:00Z\"}]}],\"qualifications\":[{\"resourceNo\":\"R\","
            + "\"sourceLineRef\":\"L\",\"goodsNo\":\"G\",\"skuNo\":null,\"required\":true}]}";

    @Test
    void versionOneRoundTripsStableExactJson() {
        String encoded = encode(demands(START), List.of(new Qualification("R", "L", "G", null)));
        assertThat(encoded).isEqualTo(JSON);
        Object plan = call("decode", encoded);
        assertThat(get(plan, "demands")).isEqualTo(demands(START));
        assertThat(get(plan, "qualifications")).isEqualTo(List.of(new Qualification("R", "L", "G", null)));
    }

    @Test
    void decodedPlanAndNestedIntervalsAreImmutable() {
        Object plan = call("decode", JSON);
        var demands = (List<?>) get(plan, "demands");
        assertThatThrownBy(demands::clear).isInstanceOf(UnsupportedOperationException.class);
        var intervals = ((Demand) demands.getFirst()).intervals();
        assertThatThrownBy(intervals::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(((List<?>) get(plan, "qualifications"))::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void hashIncludesRawIntervalsEvenInsideTheSameBucket() {
        String changed = encode(demands(START.plusSeconds(1)), List.of(new Qualification("R", "L", "G", null)));
        assertThat(call("hash", changed)).isNotEqualTo(call("hash", JSON));
        assertThat(call("hash", JSON)).asString().matches("[0-9a-f]{64}");
    }

    @Test
    void hashIncludesProjectSkuAndExplicitExemption() {
        String project = encode(demands(START), List.of(new Qualification("R", "L", "G", "SKU")));
        String exempt = encode(demands(START), List.of(new Qualification("R", "L", null, null, false)));
        assertThat(call("hash", JSON)).isNotEqualTo(call("hash", project)).isNotEqualTo(call("hash", exempt));
    }

    @Test
    void rejectsUnknownDuplicateTrailingAndWrongVersion() {
        for (String value : List.of(JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                JSON.replace("\"quantity\":1", "\"quantity\":1,\"hidden\":true"), JSON + "{}")) {
            assertThatThrownBy(() -> call("decode", value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsCoercionsMissingFieldsAndNullStructure() {
        for (String value : List.of(JSON.replace("\"quantity\":1", "\"quantity\":\"1\""),
                JSON.replace("\"quantity\":1", "\"quantity\":1.0"),
                JSON.replace("\"required\":true", "\"required\":\"true\""),
                JSON.replace("\"skuNo\":null,", ""), JSON.replace("\"demands\":[", "\"demands\":null,\"unused\":["))) {
            assertThatThrownBy(() -> call("decode", value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidQuantityTimeAndNanoseconds() {
        for (String value : List.of(JSON.replace("\"quantity\":1", "\"quantity\":0"),
                JSON.replace("10:05:00Z", "09:55:00Z"), JSON.replace("10:00:00Z", "10:00:00.000001Z"))) {
            assertThatThrownBy(() -> call("decode", value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDuplicateGroupsAndMixedQualificationPolicy() {
        var demand = demands(START).getFirst();
        assertThatThrownBy(() -> encode(List.of(demand, demand), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encode(demands(START), List.of(new Qualification("R", "L", "G", null),
                new Qualification("R", "L", null, null, false)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsUtf8AndRawIntervalsBeforeExpansion() {
        assertThatThrownBy(() -> call("decode", " ".repeat(65537))).isInstanceOf(IllegalArgumentException.class);
        var intervals = IntStream.range(0, 10001)
                .mapToObj(i -> new Interval(START.plusSeconds(i), START.plusSeconds(i + 1))).toList();
        assertThatThrownBy(() -> encode(List.of(new Demand("R", "L", 1, intervals)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyPlansAndForeignQualificationGroupsAreRejected() {
        assertThatThrownBy(() -> encode(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encode(demands(START), List.of(new Qualification("OTHER", "L", "G", null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adapterRejectsOversizedDemandCollectionBeforeReadingItsElements() {
        List<Demand> oversized = new AbstractList<>() {
            @Override
            public int size() {
                return 10001;
            }

            @Override
            public Demand get(int index) {
                throw new AssertionError("oversized evidence must not be traversed");
            }
        };
        assertThatThrownBy(() -> ResourcePlanCodec.encode(oversized, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adapterRejectsOversizedIntervalsBeforeReadingTheirElements() {
        List<Interval> oversized = new AbstractList<>() {
            @Override
            public int size() {
                return 10001;
            }

            @Override
            public Interval get(int index) {
                throw new AssertionError("oversized intervals must not be traversed");
            }
        };
        assertThatThrownBy(() -> ResourcePlanCodec.encode(List.of(new Demand("R", "L", 1, oversized)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceBindingRequiresCompleteCanonicalEvidence() {
        Object binding = binding("RESERVE", "a".repeat(64), 1);
        assertThat(get(binding, "commandNo")).isEqualTo("C");
        assertThatThrownBy(() -> binding("reserve", "a".repeat(64), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding("RESERVE", "A".repeat(64), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding("RESERVE", "a".repeat(64), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorizedDecisionRequiresDurableFenceAndEffectiveWindow() {
        Object value = decision("AUTHORIZED", "D", "F", 1L, START, START.plusSeconds(60), null);
        assertThat(get(value, "fenceRevision")).isEqualTo(1L);
        assertThatThrownBy(() -> decision("AUTHORIZED", "D", null, 1L, START, START.plusSeconds(60), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision("AUTHORIZED", "D", "F", -1L, START, START.plusSeconds(60), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision("AUTHORIZED", "D", "F", 1L, START, START, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownPendingAndDeniedCannotSmugglePermissionEvidence() {
        for (String state : List.of("UNKNOWN", "PENDING", "DENIED")) {
            assertThatThrownBy(() -> decision(state, "D", "F", 1L, START, START.plusSeconds(60), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(get(decision("UNKNOWN", null, null, null, null, null, null), "state").toString())
                .isEqualTo("UNKNOWN");
        assertThat(get(decision("DENIED", "D", null, null, null, null, "SOURCE_CANCELLED"), "reasonCode"))
                .isEqualTo("SOURCE_CANCELLED");
    }

    @Test
    void requestAlwaysCarriesTheCurrentRecoveryOperator() {
        Object binding = binding("RESERVE", "a".repeat(64), 1);
        Object request = construct(PORT + "$Request", binding, "RECOVERY", "MERCHANT", "MAC");
        assertThat(get(request, "operatorNo")).isEqualTo("RECOVERY");
        assertThatThrownBy(() -> construct(PORT + "$Request", binding, " ", "MERCHANT", "MAC"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<Demand> demands(Instant start) {
        return List.of(new Demand("R", "L", 1, List.of(new Interval(start, START.plusSeconds(300)))));
    }

    private static String encode(List<Demand> demands, List<Qualification> qualifications) {
        return (String) call("encode", demands, qualifications);
    }

    private static Object binding(String action, String hash, long revision) {
        return construct(PORT + "$Binding", "MAIN", "E", "S", "C", "BEAUTY_APPOINTMENT", "A", revision,
                action, hash, "b".repeat(64));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decision(String state, String decision, String fence, Long revision, Instant issued,
            Instant until, String reason) {
        var value = Enum.valueOf((Class) required(PORT + "$State"), state);
        return construct(PORT + "$Decision", binding("RESERVE", "a".repeat(64), 1), value, decision, fence,
                revision, issued, until, reason);
    }

    private static Object construct(String type, Object... args) {
        return invoke(() -> required(type).getConstructors()[0].newInstance(args));
    }

    private static Object call(String name, Object... args) {
        for (var method : required(CODEC).getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                return invoke(() -> method.invoke(null, args));
            }
        }
        throw new AssertionError("missing codec method: " + name);
    }

    private static Object get(Object value, String name) {
        return invoke(() -> value.getClass().getMethod(name).invoke(value));
    }

    private static Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName(name));
    }

    private static Object invoke(ReflectiveCall action) {
        try {
            return action.run();
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private interface ReflectiveCall {
        Object run() throws ReflectiveOperationException;
    }
}
