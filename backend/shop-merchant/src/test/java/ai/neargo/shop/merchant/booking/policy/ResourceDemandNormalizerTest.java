package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.Type;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real normalization behavior; reflection lets missing production types fail as assertions in the first RED run. */
class ResourceDemandNormalizerTest {
    private static final String OWNER = "ai.neargo.shop.merchant.policy.ResourceDemandNormalizer";
    private static final Instant START = Instant.parse("2026-10-21T09:00:00Z");

    @Test
    void roundsOutwardWithoutAddingAnAlignedEnd() {
        Object plan = normalize(List.of(demand("R", "L", 1, interval(120000, 3780000))), facts(1));
        List<?> units = list(plan, "units");
        assertEquals(13, units.size());
        assertEquals(START, get(units.getFirst(), "startAt"));
        assertEquals(START.plusSeconds(3900), get(units.getLast(), "endAt"));
        assertEquals(1, list(normalize(List.of(demand("R", "L", 1, interval(0, 300000))),
                facts(1)), "units").size());
    }

    @Test
    void preservesOneMillisecondAtTheBoundary() {
        Object plan = normalize(List.of(demand("R", "L", 1, interval(299999, 300001))), facts(1));
        assertEquals(2, list(plan, "units").size());
        assertEquals(START, get(list(plan, "units").getFirst(), "startAt"));
        rejects(List.of(demand("R", "L", 1,
                range(START, START.plusSeconds(300).plusNanos(1)))), facts(1));
    }

    @Test
    void handlesPreEpochAndDstAsUtc() {
        Object beforeEpoch = normalize(List.of(demand("R", "L", 1,
                range(Instant.ofEpochMilli(-1), Instant.ofEpochMilli(1)))), facts(1));
        assertEquals(Instant.parse("1969-12-31T23:55:00Z"),
                get(list(beforeEpoch, "units").getFirst(), "startAt"));
        assertEquals(2, list(beforeEpoch, "units").size());
        Object dst = normalize(List.of(demand("R", "L", 1,
                range(Instant.parse("2026-11-01T05:55:00Z"), Instant.parse("2026-11-01T06:05:00Z")))),
                facts(1));
        assertEquals(2, list(dst, "units").size());
    }

    @Test
    void mergesOneGroupButAddsIndependentGroups() {
        Object plan = normalize(List.of(
                demand("R", "SERVICE", 1, interval(0, 600000), interval(300000, 900000), interval(900000, 1200000)),
                demand("R", "OTHER", 2, interval(600000, 900000))), facts(3));
        assertEquals(5, list(plan, "units").size());
        assertEquals(List.of(1, 1, 3, 1), list(plan, "buckets").stream().map(b -> get(b, "quantity")).toList());
    }

    @Test
    void disconnectedFragmentsInOneBucketUseOneUnitButDifferentGroupsAdd() {
        Object plan = normalize(List.of(demand("R", "A", 1, interval(0, 60000), interval(120000, 180000)),
                demand("R", "B", 1, interval(240000, 300000))), facts(2));
        assertEquals(2, list(plan, "units").size());
        assertEquals(2, get(list(plan, "buckets").getFirst(), "quantity"));
    }

    @Test
    void rejectsDuplicateOrContradictoryGroups() {
        rejects(List.of(demand("R", "L", 1, interval(0, 300000)),
                demand("R", "L", 1, interval(300000, 600000))), facts(2));
        rejects(List.of(demand("R", "L", 1, interval(0, 300000)),
                demand("R", "L", 2, interval(300000, 600000))), facts(2));
        rejects(List.of(demand("R", "L", 1, interval(0, 300000), interval(0, 300000))), facts(2));
    }

    @Test
    void rejectsCapacityOverflowAcrossGroups() {
        rejects(List.of(demand("R", "A", 1, interval(0, 300000)),
                demand("R", "B", 1, interval(0, 300000))), facts(1));
        rejects(List.of(demand("R", "A", Integer.MAX_VALUE, interval(0, 300000)),
                demand("R", "B", 1, interval(0, 300000))), facts(Integer.MAX_VALUE));
    }

    @Test
    void rejectsInvalidQuantitiesAndResourceFacts() {
        for (int quantity : List.of(0, -1, 2)) {
            rejects(List.of(demand("R", "L", quantity, interval(0, 300000))), facts(1));
        }
        rejects(List.of(demand("R", "L", 1, interval(0, 300000))), facts(0));
        rejects(List.of(demand("R", "L", 1, interval(0, 300000))),
                List.of(new ResourceFact("R", Type.STAFF, null, "P", "staff", 2, false)));
    }

    @Test
    void requiresExactCanonicalResourceIdentity() {
        rejects(List.of(demand("r", "L", 1, interval(0, 300000))), facts(1));
        rejects(List.of(demand("R", "L", 1, interval(0, 300000))), List.of());
        rejects(List.of(demand("R", "L", 1, interval(0, 300000))), List.of(fact("R", 1), fact("R", 1)));
    }

    @Test
    void keepsGroupReferencesOpaqueAndCaseSensitive() {
        Object plan = normalize(List.of(demand("R", "A", 1, interval(0, 300000)),
                demand("R", "a", 1, interval(0, 300000))), facts(2));
        assertEquals(2, list(plan, "units").size());
        for (String invalid : List.of("", " ", " A", "A ", "x".repeat(129))) {
            rejects(List.of(demand("R", invalid, 1, interval(0, 300000))), facts(1));
        }
    }

    @Test
    void rejectsEmptyNullAndInvalidIntervals() {
        rejects(null, facts(1));
        rejects(List.of(), facts(1));
        rejects(List.of(demand("R", "L", 1)), facts(1));
        rejects(List.of(demand("R", "L", 1, interval(300000, 300000))), facts(1));
        rejects(List.of(demand("R", "L", 1, interval(600000, 300000))), facts(1));
        rejects(Collections.singletonList(null), facts(1));
        rejects(List.of(demand("R", "L", 1, range(null, START))), facts(1));
        rejects(List.of(demand("R", "L", 1, interval(0, 300000))), null);
    }

    @Test
    void rejectsDatesOutsideStorageSafeRange() {
        rejects(List.of(demand("R", "L", 1, range(Instant.MIN, START))), facts(1));
        rejects(List.of(demand("R", "L", 1, range(START, Instant.MAX))), facts(1));
        assertEquals(1, list(normalize(List.of(demand("R", "L", 1,
                range(Instant.parse("1000-01-03T00:00:00Z"), Instant.parse("1000-01-03T00:05:00Z")))),
                facts(1)), "units").size());
    }

    @Test
    void enforcesResourceAndUnitLimits() {
        List<Object> demands = new ArrayList<>();
        List<ResourceFact> facts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            facts.add(fact("R" + i, 1));
            demands.add(demand("R" + i, "L", 1, interval(0, 300000)));
        }
        assertEquals(10, list(normalize(demands, facts), "units").size());
        facts.add(fact("R10", 1));
        demands.add(demand("R10", "L", 1, interval(0, 300000)));
        rejects(demands, facts);
        assertEquals(10000, list(normalize(List.of(demand("R", "L", 1, interval(0, 3000000000L))),
                facts(1)), "units").size());
        rejects(List.of(demand("R", "L", 1, interval(0, 3000000001L))), facts(1));
        rejects(List.of(demand("R", "A", 1, interval(0, 1500000000L)),
                demand("R", "B", 1, interval(1500000000L, 3000300000L))), facts(1));
    }

    @Test
    void boundsRawInputBeforeExpandingOverlappingIntervals() {
        List<Object> intervals = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            intervals.add(interval(i, 3000000000L - i));
        }
        Object demand = construct("Demand", new Class<?>[] {String.class, String.class, int.class, List.class},
                "R", "L", 1, intervals);
        assertEquals(10000, list(normalize(List.of(demand), facts(1)), "units").size());
        intervals.add(interval(20000, 3000000000L));
        rejects(List.of(demand), facts(1));
        rejects(Collections.nCopies(10001, demand), facts(1));
    }

    @Test
    void producesDeterministicImmutablePlansWithoutMutatingInputs() {
        List<Object> inputs = new ArrayList<>(List.of(demand("R", "B", 1, interval(300000, 600000)),
                demand("R", "A", 1, interval(0, 300000))));
        Object first = normalize(inputs, facts(1));
        Collections.reverse(inputs);
        assertEquals(first, normalize(inputs, facts(1)));
        assertEquals("A", get(list(first, "units").getFirst(), "sourceLineRef"));
        assertThrows(UnsupportedOperationException.class, () -> list(first, "units").clear());
        assertThrows(UnsupportedOperationException.class, () -> list(first, "buckets").clear());
        assertEquals("A", get(inputs.getFirst(), "sourceLineRef"));
        assertTrue(list(first, "units").stream().allMatch(u -> get(u, "resourceNo").equals("R")));
    }

    private static List<ResourceFact> facts(int capacity) {
        return List.of(fact("R", capacity));
    }

    private static ResourceFact fact(String no, int capacity) {
        return new ResourceFact(no, Type.ROOM, "S", null, "room", capacity, false);
    }

    private static Object interval(long start, long end) {
        return range(START.plusMillis(start), START.plusMillis(end));
    }

    private static Object range(Instant start, Instant end) {
        return construct("Interval", new Class<?>[] {Instant.class, Instant.class}, start, end);
    }

    private static Object demand(String resource, String line, int quantity, Object... intervals) {
        return construct("Demand", new Class<?>[] {String.class, String.class, int.class, List.class},
                resource, line, quantity, List.of(intervals));
    }

    private static Object construct(String type, Class<?>[] signature, Object... args) {
        try {
            return required(OWNER + "$" + type).getConstructor(signature).newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object normalize(List<?> demands, List<ResourceFact> facts) {
        try {
            return required(OWNER).getMethod("normalize", List.class, List.class).invoke(null, demands, facts);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object get(Object value, String accessor) {
        try {
            return value.getClass().getMethod(accessor).invoke(value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static List<?> list(Object value, String accessor) {
        return (List<?>) get(value, accessor);
    }

    private static void rejects(List<?> demands, List<ResourceFact> facts) {
        assertThrows(IllegalArgumentException.class, () -> normalize(demands, facts));
    }

    private static Class<?> required(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError("required resource normalization contract missing: " + name, ex);
        }
    }
}
