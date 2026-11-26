package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Produces a deterministic capacity demand plan shared by industries, without selling availability.
 * Callers supply owner-resolved canonical facts and industry-defined continuous-use groups. Scope, schedules,
 * current time, open occupations and current capacity must still be validated by the future locked arbiter.
 */
public final class ResourceDemandNormalizer {
    private static final long BUCKET_MILLIS = 300_000L;
    private static final int MAX_RESOURCES = 10;
    private static final int MAX_UNITS = 10_000;
    private static final Instant MIN_TIME = Instant.parse("1000-01-03T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-29T00:00:00Z");
    private static final Comparator<BucketKey> BUCKET_ORDER = Comparator.comparing(BucketKey::resourceNo)
            .thenComparingLong(BucketKey::startMillis);

    private ResourceDemandNormalizer() {
    }

    /**
     * Rejects the entire plan on malformed or excessive input; exact canonical identity is mandatory because
     * Java case folding cannot emulate database collation. Snapshot capacity only bounds this proposed plan.
     */
    public static Plan normalize(List<Demand> demands, List<ResourceFact> facts) {
        Map<String, ResourceFact> resources = ResourceDemandNormalizer.validateFacts(facts);
        ResourceDemandNormalizer.validateDemands(demands, resources);
        List<Unit> units = new ArrayList<>();
        Map<BucketKey, Integer> totals = new TreeMap<>(BUCKET_ORDER);
        for (Demand demand : demands) {
            for (Span span : ResourceDemandNormalizer.bucketSpans(demand.intervals())) {
                long count = (span.endMillis() - span.startMillis()) / BUCKET_MILLIS;
                if (count > MAX_UNITS - units.size()) {
                    throw new IllegalArgumentException("normalized unit limit exceeded");
                }
                for (long start = span.startMillis(); start < span.endMillis(); start += BUCKET_MILLIS) {
                    BucketKey key = new BucketKey(demand.resourceNo(), start);
                    long total = (long) totals.getOrDefault(key, 0) + demand.quantity();
                    if (total > resources.get(demand.resourceNo()).capacity()) {
                        throw new IllegalArgumentException("combined demand exceeds resource capacity");
                    }
                    totals.put(key, (int) total);
                    units.add(new Unit(demand.resourceNo(), demand.sourceLineRef(), Instant.ofEpochMilli(start),
                            Instant.ofEpochMilli(start + BUCKET_MILLIS), demand.quantity()));
                }
            }
        }
        units.sort(Comparator.comparing(Unit::resourceNo).thenComparing(Unit::startAt)
                .thenComparing(Unit::sourceLineRef));
        List<BucketDemand> buckets = totals.entrySet().stream().map(entry -> new BucketDemand(
                entry.getKey().resourceNo(), Instant.ofEpochMilli(entry.getKey().startMillis()),
                Instant.ofEpochMilli(entry.getKey().startMillis() + BUCKET_MILLIS), entry.getValue())).toList();
        return new Plan(units, buckets);
    }

    private static Map<String, ResourceFact> validateFacts(List<ResourceFact> facts) {
        if (facts == null || facts.isEmpty() || facts.size() > MAX_RESOURCES) {
            throw new IllegalArgumentException("1..10 canonical resource facts required");
        }
        Map<String, ResourceFact> resources = new HashMap<>();
        for (ResourceFact fact : facts) {
            if (fact == null || fact.resourceNo() == null || fact.resourceNo().isBlank()
                    || fact.resourceType() == null || fact.capacity() <= 0
                    || fact.resourceType() == Type.STAFF && fact.capacity() != 1
                    || resources.putIfAbsent(fact.resourceNo(), fact) != null) {
                throw new IllegalArgumentException("invalid or duplicate canonical resource fact");
            }
        }
        return resources;
    }

    private static void validateDemands(List<Demand> demands, Map<String, ResourceFact> resources) {
        if (demands == null || demands.isEmpty() || demands.size() > MAX_UNITS) {
            throw new IllegalArgumentException("1..10000 demand groups required");
        }
        Set<GroupKey> groups = new HashSet<>();
        int intervalCount = 0;
        for (Demand demand : demands) {
            if (demand == null || demand.resourceNo() == null || !resources.containsKey(demand.resourceNo())
                    || demand.sourceLineRef() == null || demand.sourceLineRef().isBlank()
                    || demand.sourceLineRef().length() > 128
                    || !demand.sourceLineRef().equals(demand.sourceLineRef().strip())
                    || demand.quantity() <= 0 || demand.quantity() > resources.get(demand.resourceNo()).capacity()
                    || demand.intervals() == null || demand.intervals().isEmpty()
                    || !groups.add(new GroupKey(demand.resourceNo(), demand.sourceLineRef()))) {
                throw new IllegalArgumentException("invalid, duplicate or contradictory demand group");
            }
            if (demand.intervals().size() > MAX_UNITS - intervalCount) {
                throw new IllegalArgumentException("raw interval limit exceeded");
            }
            intervalCount += demand.intervals().size();
            Set<Interval> distinct = new HashSet<>();
            for (Interval interval : demand.intervals()) {
                if (interval == null || interval.startAt() == null || interval.endAt() == null
                        || !interval.startAt().isBefore(interval.endAt())
                        || interval.startAt().isBefore(MIN_TIME) || interval.endAt().isAfter(MAX_TIME)
                        || interval.startAt().getNano() % 1_000_000 != 0
                        || interval.endAt().getNano() % 1_000_000 != 0 || !distinct.add(interval)) {
                    throw new IllegalArgumentException(
                            "distinct storage-safe millisecond half-open intervals required");
                }
            }
        }
    }

    /** Merge bucket spans before expansion so many overlapping input fragments do not cause quadratic work. */
    private static List<Span> bucketSpans(List<Interval> intervals) {
        List<Span> spans = new ArrayList<>();
        for (Interval interval : intervals) {
            long start = Math.floorDiv(interval.startAt().toEpochMilli(), BUCKET_MILLIS) * BUCKET_MILLIS;
            long end = interval.endAt().toEpochMilli();
            long remainder = Math.floorMod(end, BUCKET_MILLIS);
            if (remainder != 0) {
                end += BUCKET_MILLIS - remainder;
            }
            spans.add(new Span(start, end));
        }
        spans.sort(Comparator.comparingLong(Span::startMillis).thenComparingLong(Span::endMillis));
        List<Span> merged = new ArrayList<>();
        for (Span next : spans) {
            if (!merged.isEmpty() && next.startMillis() <= merged.getLast().endMillis()) {
                Span last = merged.getLast();
                merged.set(merged.size() - 1,
                        new Span(last.startMillis(), Math.max(last.endMillis(), next.endMillis())));
            } else {
                merged.add(next);
            }
        }
        return merged;
    }

    /** Industry-composed resource use, including preparation and cleanup; normalization never guesses SKU grouping. */
    public record Demand(String resourceNo, String sourceLineRef, int quantity, List<Interval> intervals) {
    }

    /** UTC half-open use interval; normalize rejects fractional milliseconds rather than shortening occupancy. */
    public record Interval(Instant startAt, Instant endAt) {
    }

    /** Per-group evidence preserved for future hold-unit ownership and release, never summed twice for one group. */
    public record Unit(String resourceNo, String sourceLineRef, Instant startAt, Instant endAt, int quantity) {
    }

    /** Resource-global bucket requirement; independent groups conservatively add even for disjoint minute spans. */
    public record BucketDemand(String resourceNo, Instant startAt, Instant endAt, int quantity) {
    }

    /** Immutable proposed allocation, not a reservation or permission to begin execution. */
    public record Plan(List<Unit> units, List<BucketDemand> buckets) {
        public Plan {
            units = List.copyOf(units);
            buckets = List.copyOf(buckets);
        }
    }

    private record GroupKey(String resourceNo, String sourceLineRef) {
    }

    private record BucketKey(String resourceNo, long startMillis) {
    }

    private record Span(long startMillis, long endMillis) {
    }
}
