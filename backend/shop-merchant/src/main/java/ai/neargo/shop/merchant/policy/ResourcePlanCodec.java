package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Interval;
import ai.neargo.shop.merchant.service.ResourceAdmissionService.Qualification;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict versioned source evidence; raw interval details and qualification exemptions must survive freezing. */
public final class ResourcePlanCodec {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ResourcePlanCodec() {
    }

    /** Stable field encoding without guessing groups or rewriting industry semantics. */
    public static String encode(List<Demand> demands, List<Qualification> qualifications) {
        var plan = new FrozenPlan(demands, qualifications);
        var root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        var rows = root.putArray("demands");
        for (var demand : plan.demands()) {
            var row = rows.addObject();
            row.put("resourceNo", demand.resourceNo());
            row.put("sourceLineRef", demand.sourceLineRef());
            row.put("quantity", demand.quantity());
            var intervals = row.putArray("intervals");
            for (var interval : demand.intervals()) {
                var span = intervals.addObject();
                span.put("startAt", interval.startAt().toString());
                span.put("endAt", interval.endAt().toString());
            }
        }
        var requirements = root.putArray("qualifications");
        for (var qualification : plan.qualifications()) {
            var row = requirements.addObject();
            row.put("resourceNo", qualification.resourceNo());
            row.put("sourceLineRef", qualification.sourceLineRef());
            row.put("goodsNo", qualification.goodsNo());
            row.put("skuNo", qualification.skuNo());
            row.put("required", qualification.required());
        }
        String result = JSON.writeValueAsString(root);
        ResourcePlanCodec.bounded(result);
        return result;
    }

    /** Rejects schema drift/coercions/partial evidence; old arbitrary command payloads are not upgraded. */
    public static FrozenPlan decode(String frozen) {
        ResourcePlanCodec.bounded(frozen);
        try {
            var root = JSON.readTree(frozen);
            ResourcePlanCodec.fields(root, "schemaVersion", "demands", "qualifications");
            if (ResourcePlanCodec.integer(root.get("schemaVersion")) != 1) {
                throw new IllegalArgumentException("unsupported resource plan version");
            }
            var rows = root.get("demands");
            var requirements = root.get("qualifications");
            if (!rows.isArray() || rows.isEmpty() || rows.size() > 10000
                    || !requirements.isArray() || requirements.size() > 10000) {
                throw new IllegalArgumentException("bounded plan arrays required");
            }
            List<Demand> demands = new ArrayList<>();
            int count = 0;
            for (var row : rows) {
                ResourcePlanCodec.fields(row, "resourceNo", "sourceLineRef", "quantity", "intervals");
                var spans = row.get("intervals");
                if (!spans.isArray() || spans.isEmpty() || spans.size() > 10000 - count) {
                    throw new IllegalArgumentException("bounded raw intervals required");
                }
                count += spans.size();
                List<Interval> intervals = new ArrayList<>();
                for (var span : spans) {
                    ResourcePlanCodec.fields(span, "startAt", "endAt");
                    intervals.add(new Interval(Instant.parse(ResourcePlanCodec.string(span.get("startAt"))),
                            Instant.parse(ResourcePlanCodec.string(span.get("endAt")))));
                }
                demands.add(new Demand(ResourcePlanCodec.string(row.get("resourceNo")),
                        ResourcePlanCodec.string(row.get("sourceLineRef")),
                        ResourcePlanCodec.integer(row.get("quantity")), intervals));
            }
            List<Qualification> qualifications = new ArrayList<>();
            for (var row : requirements) {
                ResourcePlanCodec.fields(row, "resourceNo", "sourceLineRef", "goodsNo", "skuNo", "required");
                if (!row.get("required").isBoolean()) {
                    throw new IllegalArgumentException("explicit boolean qualification policy required");
                }
                qualifications.add(new Qualification(ResourcePlanCodec.string(row.get("resourceNo")),
                        ResourcePlanCodec.string(row.get("sourceLineRef")),
                        ResourcePlanCodec.nullable(row.get("goodsNo")), ResourcePlanCodec.nullable(row.get("skuNo")),
                        row.get("required").booleanValue()));
            }
            return new FrozenPlan(demands, qualifications);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid frozen resource plan", failure);
        }
    }

    /** Hash exact frozen UTF8 bytes with a version domain; never substitute only normalized bucket totals. */
    public static String hash(String frozen) {
        ResourcePlanCodec.decode(frozen);
        return IdempotencyScope.requestHash("RESOURCE_PLAN_V1", frozen);
    }

    /** Deep immutable input for the locked local admission guard, not permission or capacity evidence. */
    public record FrozenPlan(List<Demand> demands, List<Qualification> qualifications) {
        public FrozenPlan {
            if (demands == null || demands.isEmpty() || demands.size() > 10000
                    || qualifications == null || qualifications.size() > 10000) {
                throw new IllegalArgumentException("bounded frozen resource plan required");
            }
            List<Demand> snapshot = new ArrayList<>();
            Set<String> resources = new HashSet<>();
            Set<Group> groups = new HashSet<>();
            int count = 0;
            for (var demand : demands) {
                if (demand == null || demand.quantity() <= 0 || demand.intervals() == null
                        || demand.intervals().isEmpty() || demand.intervals().size() > 10000 - count) {
                    throw new IllegalArgumentException("positive bounded demand required");
                }
                ResourcePlanCodec.text(demand.resourceNo(), 32);
                ResourcePlanCodec.text(demand.sourceLineRef(), 128);
                resources.add(demand.resourceNo());
                if (resources.size() > 10 || !groups.add(new Group(demand.resourceNo(), demand.sourceLineRef()))) {
                    throw new IllegalArgumentException("resource limit or duplicate group");
                }
                count += demand.intervals().size();
                Set<Interval> seen = new HashSet<>();
                for (var interval : demand.intervals()) {
                    if (interval == null) {
                        throw new IllegalArgumentException("interval required");
                    }
                    ResourcePlanCodec.time(interval.startAt());
                    ResourcePlanCodec.time(interval.endAt());
                    if (!interval.endAt().isAfter(interval.startAt()) || !seen.add(interval)) {
                        throw new IllegalArgumentException("distinct positive interval required");
                    }
                }
                snapshot.add(new Demand(demand.resourceNo(), demand.sourceLineRef(), demand.quantity(),
                        List.copyOf(demand.intervals())));
            }
            var policies = new HashMap<Group, Boolean>();
            var seen = new HashSet<Qualification>();
            for (var value : qualifications) {
                if (value == null) {
                    throw new IllegalArgumentException("qualification policy required");
                }
                var group = new Group(value.resourceNo(), value.sourceLineRef());
                if (!groups.contains(group) || !seen.add(value)) {
                    throw new IllegalArgumentException("unknown or duplicate qualification group");
                }
                Boolean previous = policies.putIfAbsent(group, value.required());
                if (previous != null && (!previous || !value.required())) {
                    throw new IllegalArgumentException("exemption cannot mix with project requirements");
                }
                if (value.required()) {
                    ResourcePlanCodec.text(value.goodsNo(), 32);
                    if (value.skuNo() != null) {
                        ResourcePlanCodec.text(value.skuNo(), 32);
                    }
                } else if (value.goodsNo() != null || value.skuNo() != null) {
                    throw new IllegalArgumentException("exemption cannot carry a project");
                }
            }
            demands = List.copyOf(snapshot);
            qualifications = List.copyOf(qualifications);
        }
    }

    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) {
            throw new IllegalArgumentException("exact resource plan fields required");
        }
        for (String name : names) {
            if (!node.has(name)) {
                throw new IllegalArgumentException("missing resource plan field");
            }
        }
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isString()) {
            throw new IllegalArgumentException("string required without coercion");
        }
        return node.asString();
    }

    private static String nullable(JsonNode node) {
        return node.isNull() ? null : ResourcePlanCodec.string(node);
    }

    private static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException("integer required without coercion");
        }
        return node.intValue();
    }

    private static void bounded(String value) {
        if (value == null || value.length() > 65536 || value.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("frozen resource plan exceeds64KiB");
        }
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw new IllegalArgumentException("bounded canonical plan reference required");
        }
    }

    private static void time(Instant value) {
        if (value == null || value.getNano() % 1_000_000 != 0
                || value.isBefore(Instant.parse("1000-01-03T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-29T00:00:00Z"))) {
            throw new IllegalArgumentException("storage-safe millisecond resource time required");
        }
    }

    private record Group(String resourceNo, String sourceLineRef) {
    }
}
