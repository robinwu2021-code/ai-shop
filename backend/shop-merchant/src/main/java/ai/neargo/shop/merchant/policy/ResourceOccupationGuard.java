package ai.neargo.shop.merchant.policy;

import static tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationSegmentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationUnitMapper;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AcceptedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.PermanentUnit;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.SourceGroup;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Shared capacity and maintenance fence; only owner-local current evidence, never remote IO or owner locks. */
@Component
public final class ResourceOccupationGuard {
    private final ResourceOccupationSchemaVerifier schema;
    private final ResourceOccupationMapper rows;
    private final ResourceOccupationSegmentMapper segments;
    private final ResourceOccupationUnitMapper units;
    private final ResourceBucketMapper buckets;
    private final ResourceOccupationBucketMapper projections;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(STRICT_DUPLICATE_DETECTION)
            .enable(FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(FAIL_ON_TRAILING_TOKENS).build();

    public ResourceOccupationGuard(ResourceOccupationSchemaVerifier schema, ResourceOccupationMapper rows,
            ResourceOccupationSegmentMapper segments, ResourceOccupationUnitMapper units, ResourceBucketMapper buckets,
            ResourceOccupationBucketMapper projections) {
        this.schema = schema;
        this.rows = rows;
        this.segments = segments;
        this.units = units;
        this.buckets = buckets;
        this.projections = projections;
    }

    /** Inspect once per operation under its existing roots; callers cannot cache across owner operations. */
    public boolean inspect(ResourceCommandScopeRow scope) {
        var installation = requireCommon(scope);
        if (installation == ResourceOccupationSchemaVerifier.Installation.INSTALLED) {
            inspectNativeEvidence(scope);
            return true;
        }
        return false;
    }

    /** Native arbiter calls after metadata inspection outside its root transaction. */
    public void inspectNativeEvidence(ResourceCommandScopeRow scope) {
        if (evidence(() -> rows.countInvalid(scope)) != 0) { fail(); }
        for (var segment : evidence(() -> segments.selectScopeOriginals(scope))) {
            var tree = evidence(() -> JSON.readTree(segment.getPlanJson()));
            var version = tree.has("schema") ? tree.path("schema").asString() : "";
            if (ResourcePhysicalProtectionPlan.SCHEMA.equals(version)) {
                var document = evidence(() -> JSON.readValue(segment.getPlanJson(), ResourcePhysicalProtectionPlan.Document.class));
                var parent = evidence(() -> rows.selectPhysicalOriginal(scope, segment.getOccupationNo()));
                if (parent == null) fail();
                var accepted = evidence(() -> JSON.readValue(parent.frozenPayload(),
                        AcceptedStart.class));
                evidence(() -> { accepted.validate(); return true; });
                var source = accepted.source();
                if (!parent.initialPlanJson().equals(document.originalTaskPlanJson())
                        || !parent.planHash().equals(document.originalTaskPlanHash())
                        || !source.originalTaskPlanSchema().equals(document.originalTaskPlanSchema())
                        || !source.resourcePlanJson().equals(document.resourcePlanJson())
                        || document.sourceUnits().stream().anyMatch(u -> !(source.booking() == null ? "DIRECT" : "BOOKING").equals(u.origin()))
                        || !segment.getSourceUnitsJson().equals(JSON.writeValueAsString(document.sourceUnits().stream()
                                .map(PermanentUnit::occupationUnitNo).sorted().toList()))
                        || !source.sourceGroups().stream().filter(g -> g.resourceNo().equals(segment.getResourceNo()))
                        .sorted(Comparator.comparing(SourceGroup::sourceLineRef))
                        .toList().equals(document.sourceGroups())) fail();
                var originalUnits = evidence(() -> units.selectOriginal(scope, segment.getOccupationNo())).stream()
                        .filter(u -> segment.getLogicalDemandNo().equals(u.getLogicalDemandNo())).toList();
                evidence(() -> { ResourcePhysicalProtectionPlan.requireMapping(document, segment, originalUnits); return true; });
                var physical = ResourcePhysicalProtectionPlan.from(
                        ResourcePlanContract.decode(document.resourcePlanJson()), document.actualStartAt());
                var expectedBuckets = physical.groups().stream().filter(g -> g.resourceNo().equals(segment.getResourceNo()))
                        .findFirst().orElseThrow().buckets();
                var actualBuckets = evidence(() -> projections.selectOriginal(scope, segment.getOccupationNo())).stream()
                        .filter(p -> p.getSegmentNo().equals(segment.getSegmentNo()) && "OCCUPIED".equals(p.getCategory())).toList();
                if (expectedBuckets.size() != actualBuckets.size() || expectedBuckets.stream().anyMatch(b -> actualBuckets.stream().filter(p ->
                        p.getStartAt().toInstant(ZoneOffset.UTC).equals(b.startAt()) && p.getEndAt().toInstant(ZoneOffset.UTC).equals(b.endAt())
                                && p.getQty() == b.quantity() && "OCCUPIED".equals(p.getCategory())).count() != 1)) fail();
                // Every BOOKING mapped bucket must be an exact original normalized bucket, not merely the right resource.
                var mappedBooking = new ArrayList<String>();
                for (var mapped : document.sourceUnits()) {
                    if (!"BOOKING".equals(mapped.origin())) continue;
                    var bucket = evidence(() -> buckets.selectOccupationBucket(scope, mapped.sourceBucketNo()));
                    var from = bucket.getStartAt().toInstant(ZoneOffset.UTC);
                    var to = bucket.getEndAt().toInstant(ZoneOffset.UTC);
                    mappedBooking.add(mapped.resourceNo() + "\n" + mapped.sourceGroupRef() + "\n" + from + "\n" + to + "\n" + mapped.quantity());
                }
                if (!mappedBooking.isEmpty() && !mappedBooking.stream().sorted().toList().equals(physical.originalUnits().stream()
                        .filter(u -> u.resourceNo().equals(segment.getResourceNo())).map(u -> u.resourceNo() + "\n" + u.sourceLineRef()
                        + "\n" + u.startAt() + "\n" + u.endAt() + "\n" + u.quantity()).sorted().toList())) fail();
            } else if (ResourceWorkflowSegment.SCHEMA.equals(version)) {
                var document = evidence(() -> JSON.readValue(segment.getPlanJson(), ResourceWorkflowSegment.class));
                evidence(() -> { document.validate(); return true; });
                var original = evidence(() -> rows.selectWorkflowOriginal(scope, segment.getCommandNo()));
                if (original == null) fail();
                var frozen = evidence(() -> JSON.readTree(original.frozenPayload()));
                if (!JSON.writeValueAsString(document.request()).equals(frozen.path("request").toString())
                        || !JSON.writeValueAsString(document.authority()).equals(frozen.path("authority").toString())) fail();
                var previous = evidence(() -> segments.selectScopeOriginals(scope)).stream().filter(s ->
                        s.getOccupationNo().equals(segment.getOccupationNo())
                        && s.getSegmentNo().equals(segment.getPreviousSegmentNo())).findFirst().orElseThrow();
                if (!document.previousSegmentNo().equals(previous.getSegmentNo())
                        || !document.resourceNo().equals(segment.getResourceNo()) || document.quantity() != segment.getQty()
                        || !previous.getLogicalDemandNo().equals(segment.getLogicalDemandNo())
                        || previous.getQty().intValue() != segment.getQty().intValue()
                        || !previous.getSourceUnitsJson().equals(segment.getSourceUnitsJson())
                        || !previous.getSourceUnitsHash().equals(segment.getSourceUnitsHash())
                        || !previous.getActualEndAt().equals(segment.getActualStartAt())
                        || !document.startedAt().equals(segment.getActualStartAt().toInstant(ZoneOffset.UTC))
                        || !document.expectedEndAt().equals(segment.getExpectedEndAt().toInstant(ZoneOffset.UTC))) fail();
                var physical = ResourcePhysicalProtectionPlan.from(ResourcePlanContract.decode(
                        document.authority().replacementResourcePlanJson()),document.startedAt());
                var group = physical.groups().stream().filter(g -> g.resourceNo().equals(segment.getResourceNo())).findFirst().orElseThrow();
                var actual = evidence(() -> projections.selectOriginal(scope,segment.getOccupationNo())).stream()
                        .filter(p -> p.getSegmentNo().equals(segment.getSegmentNo()) && "OCCUPIED".equals(p.getCategory())).toList();
                if (group.peakQty() != segment.getQty() || actual.size() != group.buckets().size()
                        || group.buckets().stream().anyMatch(b -> actual.stream().filter(p ->
                        p.getStartAt().toInstant(ZoneOffset.UTC).equals(b.startAt())
                        && p.getEndAt().toInstant(ZoneOffset.UTC).equals(b.endAt()) && p.getQty() == b.quantity()).count() != 1)) fail();
            } else if (!version.isEmpty()) {
                fail();
            }
        }
    }

    /** New binaries require the forward common contract before issuing any SQL using its columns. */
    public ResourceOccupationSchemaVerifier.Installation requireCommon(ResourceCommandScopeRow scope) {
        var installation = schema.installation();
        if (installation == ResourceOccupationSchemaVerifier.Installation.ABSENT) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        if (installation == ResourceOccupationSchemaVerifier.Installation.CORRUPT) { fail(); }
        if (installation == ResourceOccupationSchemaVerifier.Installation.UNSUPPORTED
                && evidence(() -> rows.countCommonArtifacts(scope)) != 0) { fail(); }
        return installation;
    }

    /** Pure old identity reads still work before V607; actual usage never becomes an exclusive blocker. */
    public long currentUsed(ResourceCommandScopeRow scope, String resource, LocalDateTime at) {
        if (schema.installation() == ResourceOccupationSchemaVerifier.Installation.ABSENT) { return 0; }
        if (!inspect(scope)) { return 0; }
        var start = at.withSecond(0).withNano(0).minusMinutes(at.getMinute() % 5);
        return evidence(() -> Math.addExact(Math.addExact(evidence(() -> rows.projected(scope, resource, start, "OCCUPIED")),
                evidence(() -> rows.projected(scope, resource, start, "CLEANING"))), evidence(() -> rows.missing(scope, resource, start, start.plusMinutes(5)))));
    }

    /** Includes unprojected OPEN/PAUSED and unconfirmed cleaning after expected times; may exceed capacity. */
    public long used(ResourceCommandScopeRow scope, String resource, LocalDateTime start,
            MchResourceBucket bucket, long held, long confirmed) {
        return used(scope, resource, start, bucket, held, confirmed, inspect(scope));
    }

    public long used(ResourceCommandScopeRow scope, String resource, LocalDateTime start,
            MchResourceBucket bucket, long held, long confirmed, boolean nativeAvailable) {
        if (held < 0 || confirmed < 0 || bucket.getOccupiedQty() == null || bucket.getCleaningQty() == null
                || bucket.getOccupiedQty() < 0 || bucket.getCleaningQty() < 0) { fail(); }
        long occupied = nativeAvailable ? evidence(() -> rows.projected(scope, resource, start, "OCCUPIED")) : 0;
        long cleaning = nativeAvailable ? evidence(() -> rows.projected(scope, resource, start, "CLEANING")) : 0;
        if (occupied != bucket.getOccupiedQty() || cleaning != bucket.getCleaningQty()) { fail(); }
        long overlay = nativeAvailable ? evidence(() -> rows.missing(scope, resource, start, start.plusMinutes(5))) : 0;
        if (occupied < 0 || cleaning < 0 || overlay < 0) { fail(); }
        return evidence(() -> Math.addExact(Math.addExact(Math.addExact(held, confirmed), Math.addExact(occupied, cleaning)), overlay));
    }

    /** Valid ended/cleaned permanent history is not work; damaged linkage always fences maintenance. */
    public void requireNoWork(ResourceCommandScopeRow scope, String resource) {
        if (inspect(scope) && evidence(() -> rows.countWork(scope, resource, LocalDateTime.now(ZoneOffset.UTC))) != 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /** The advisory wire has an int bound; exceeding it is unavailable, never a truncated free quantity. */
    public int reportedUsed(long used) {
        return evidence(() -> Math.toIntExact(used));
    }

    private static <T> T evidence(Supplier<T> operation) {
        try { return operation.get(); }
        catch (BizException failure) { throw failure; }
        catch (RuntimeException failure) {
            var unavailable = BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    private static void fail() { throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE); }
}
