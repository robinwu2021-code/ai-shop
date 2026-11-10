package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.merchant.entity.MchResourceOccupationSegment;
import ai.neargo.shop.merchant.entity.MchResourceOccupationUnit;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.Type;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.PermanentUnit;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.SourceGroup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One physical group per resource; permanent original group/unit facts never become physical quantities. */
public final class ResourcePhysicalProtectionPlan {
    public static final String SCHEMA = "RESOURCE_PHYSICAL_PROTECTION_V1";
    private ResourcePhysicalProtectionPlan() { }

    public static PhysicalPlan from(ResourcePlanContract.FrozenPlan plan, Instant actualStartAt) {
        var facts = plan.demands().stream().map(ResourcePlanContract.Demand::resourceNo).distinct().sorted()
                .map(no -> new ResourceFact(no, Type.ROOM, "PHYSICAL", null, no, Integer.MAX_VALUE, false)).toList();
        var original = ResourceDemandNormalizer.normalize(plan.demands().stream().map(d ->
                new ResourceDemandNormalizer.Demand(d.resourceNo(), d.sourceLineRef(), d.quantity(),
                        d.intervals().stream().map(i -> new ResourceDemandNormalizer.Interval(i.startAt(), i.endAt()))
                                .toList())).toList(), facts);
        var end = original.buckets().stream().map(ResourceDemandNormalizer.BucketDemand::endAt)
                .max(Comparator.naturalOrder()).orElseThrow();
        if (!actualStartAt.isBefore(end)) throw new IllegalStateException("resource original protection window expired");
        Map<String, Integer> peak = new TreeMap<>();
        original.buckets().forEach(bucket -> peak.merge(bucket.resourceNo(), bucket.quantity(), Math::max));
        var groups = peak.entrySet().stream().map(entry -> {
            var no = entry.getKey();
            var continuous = ResourceDemandNormalizer.normalize(List.of(new ResourceDemandNormalizer.Demand(no,
                    "PHYSICAL:" + no, entry.getValue(), List.of(new ResourceDemandNormalizer.Interval(actualStartAt, end)))), facts);
            return new PhysicalGroup(no, entry.getValue(), plan.demands().stream().filter(d -> no.equals(d.resourceNo()))
                    .sorted(Comparator.comparing(ResourcePlanContract.Demand::sourceLineRef)).toList(), continuous.buckets());
        }).toList();
        return new PhysicalPlan(actualStartAt, end, groups, original.units(),
                ExecutionEvidenceContract.hash(SCHEMA, List.of(actualStartAt, end, groups)));
    }

    public record PhysicalGroup(String resourceNo, int peakQty, List<ResourcePlanContract.Demand> sourceGroups,
            List<ResourceDemandNormalizer.BucketDemand> buckets) {
        public PhysicalGroup { sourceGroups = List.copyOf(sourceGroups); buckets = List.copyOf(buckets); }
    }
    public record PhysicalPlan(Instant actualStartAt, Instant protectionEnd, List<PhysicalGroup> groups,
            List<ResourceDemandNormalizer.Unit> originalUnits, String physicalPlanHash) {
        public PhysicalPlan { groups = List.copyOf(groups); originalUnits = List.copyOf(originalUnits); }
    }

    /** Stored immutable mapping is the exact successor of the legacy single-group segment contract. */
    public record Document(String schema, String originalTaskPlanSchema, String originalTaskPlanJson,
            String originalTaskPlanHash, String resourcePlanJson, String resourcePlanHash,
            String physicalGroupNo, String resourceNo, String personnelNo, int peakQty, Instant actualStartAt,
            Instant protectionEnd, List<SourceGroup> sourceGroups, List<PermanentUnit> sourceUnits,
            String physicalPlanHash) {
        public Document { sourceGroups = List.copyOf(sourceGroups); sourceUnits = List.copyOf(sourceUnits); }
    }

    public static void requireMapping(Document document, MchResourceOccupationSegment segment,
            List<MchResourceOccupationUnit> units) {
        var physical = from(ResourcePlanContract.decode(document.resourcePlanJson()), document.actualStartAt());
        var group = physical.groups().stream().filter(g -> g.resourceNo().equals(document.resourceNo())).findFirst().orElseThrow();
        var byNo = units.stream().collect(Collectors.toMap(MchResourceOccupationUnit::getOccupationUnitNo, Function.identity()));
        var mapped = document.sourceUnits();
        boolean valid = SCHEMA.equals(document.schema()) && document.resourcePlanHash().equals(ResourcePlanContract.hash(document.resourcePlanJson()))
                && document.physicalPlanHash().equals(physical.physicalPlanHash())
                && document.protectionEnd().equals(physical.protectionEnd()) && document.peakQty() == group.peakQty()
                && document.physicalGroupNo().equals(segment.getLogicalDemandNo())
                && document.resourceNo().equals(segment.getResourceNo()) && document.peakQty() == segment.getQty()
                && Objects.equals(document.personnelNo(), segment.getPersonnelNo())
                && document.actualStartAt().equals(segment.getActualStartAt().toInstant(ZoneOffset.UTC))
                && document.protectionEnd().equals(segment.getExpectedEndAt().toInstant(ZoneOffset.UTC))
                && mapped.size() == byNo.size() && mapped.stream().map(PermanentUnit::occupationUnitNo).distinct().count() == mapped.size()
                && document.sourceGroups().stream().map(SourceGroup::sourceLineRef).sorted().toList().equals(
                        group.sourceGroups().stream().map(ResourcePlanContract.Demand::sourceLineRef).sorted().toList());
        for (var entry : mapped) {
            var unit = byNo.get(entry.occupationUnitNo());
            valid &= unit != null && entry.logicalDemandNo().equals(segment.getLogicalDemandNo())
                    && entry.resourceNo().equals(segment.getResourceNo())
                    && unit.getOrigin().equals(entry.origin()) && unit.getQty() == entry.quantity()
                    && Objects.equals(unit.getReservationUnitNo(), entry.reservationUnitNo())
                    && Objects.equals(unit.getHoldNo(), entry.holdNo())
                    && Objects.equals(unit.getBookingOwnerNo(), entry.bookingOwnerNo())
                    && Objects.equals(unit.getSourceLineRef(), entry.sourceLineRef())
                    && Objects.equals(unit.getAllocationRevision(), entry.allocationRevision())
                    && Objects.equals(unit.getSourceBucketNo(), entry.sourceBucketNo())
                    && (!"BOOKING".equals(entry.origin()) || entry.sourceGroupRef().equals(entry.sourceLineRef()))
                    && unit.getConsumedAt().toInstant(ZoneOffset.UTC).equals(entry.consumedAt())
                    && group.sourceGroups().stream().anyMatch(d -> d.sourceLineRef().equals(entry.sourceGroupRef()));
        }
        for (var original : group.sourceGroups()) {
            var members = mapped.stream().filter(u -> u.sourceGroupRef().equals(original.sourceLineRef())).toList();
            valid &= !members.isEmpty() && members.stream().allMatch(u -> u.quantity() == original.quantity());
            if (members.stream().anyMatch(u -> "DIRECT".equals(u.origin()))) valid &= members.size() == 1;
        }
        if (!valid) throw new IllegalStateException("invalid physical occupation original mapping");
    }
}
