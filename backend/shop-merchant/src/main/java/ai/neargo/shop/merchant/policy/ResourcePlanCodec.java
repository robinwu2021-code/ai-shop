package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Interval;
import ai.neargo.shop.merchant.service.ResourceAdmissionService.Qualification;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import java.util.List;

/** Preserves merchant callers while independent industries share the same frozen evidence contract. */
public final class ResourcePlanCodec {
    private ResourcePlanCodec() {
    }

    /** Converts internal admission inputs without changing their serialized source evidence. */
    public static String encode(List<Demand> demands, List<Qualification> qualifications) {
        var plan = shared(demands, qualifications);
        return ResourcePlanContract.encode(plan.demands(), plan.qualifications());
    }

    /** Keeps the existing admission-facing types and delegates strict validation to the common owner. */
    public static FrozenPlan decode(String frozen) {
        var plan = ResourcePlanContract.decode(frozen);
        return local(plan);
    }

    /** Uses the identical versioned byte digest for source authorities and the merchant consumer. */
    public static String hash(String frozen) {
        return ResourcePlanContract.hash(frozen);
    }

    /** Immutable local view; neither this record nor the common codec grants source permission. */
    public record FrozenPlan(List<Demand> demands, List<Qualification> qualifications) {
        public FrozenPlan {
            var plan = shared(demands, qualifications);
            demands = localDemands(plan);
            qualifications = localQualifications(plan);
        }
    }

    private static ResourcePlanContract.FrozenPlan shared(List<Demand> demands, List<Qualification> qualifications) {
        if (demands == null || demands.isEmpty() || demands.size() > 10000
                || qualifications == null || qualifications.size() > 10000) {
            throw new IllegalArgumentException("bounded frozen resource plan required");
        }
        int intervals = 0;
        for (var demand : demands) {
            if (demand == null || demand.intervals() == null || demand.intervals().isEmpty()
                    || demand.intervals().size() > 10000 - intervals) {
                throw new IllegalArgumentException("positive bounded demand required");
            }
            intervals += demand.intervals().size();
        }
        return new ResourcePlanContract.FrozenPlan(demands.stream().map(value -> value == null ? null
                : new ResourcePlanContract.Demand(value.resourceNo(), value.sourceLineRef(), value.quantity(),
                        value.intervals() == null ? null : value.intervals().stream().map(span -> span == null ? null
                                : new ResourcePlanContract.Interval(span.startAt(), span.endAt())).toList())).toList(),
                qualifications.stream().map(value -> value == null ? null
                        : new ResourcePlanContract.Qualification(value.resourceNo(), value.sourceLineRef(),
                                value.goodsNo(), value.skuNo(), value.required())).toList());
    }

    private static FrozenPlan local(ResourcePlanContract.FrozenPlan plan) {
        return new FrozenPlan(localDemands(plan), localQualifications(plan));
    }

    private static List<Demand> localDemands(ResourcePlanContract.FrozenPlan plan) {
        return plan.demands().stream().map(value -> new Demand(value.resourceNo(), value.sourceLineRef(),
                value.quantity(), value.intervals().stream().map(span -> new Interval(span.startAt(), span.endAt()))
                        .toList())).toList();
    }

    private static List<Qualification> localQualifications(ResourcePlanContract.FrozenPlan plan) {
        return plan.qualifications().stream().map(value -> new Qualification(value.resourceNo(), value.sourceLineRef(),
                value.goodsNo(), value.skuNo(), value.required())).toList();
    }
}
