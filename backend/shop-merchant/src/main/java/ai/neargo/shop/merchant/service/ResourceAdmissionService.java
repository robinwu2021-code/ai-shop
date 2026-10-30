package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Demand;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer.Plan;
import ai.neargo.shop.merchant.service.ResourceCommandService.Execution;
import java.util.List;

/** Locked local facts only; the later processor must bind complete demands/qualifications to frozen source intent. */
public interface ResourceAdmissionService {
    /**
     * Acquires personnel then canonical resource roots within the original command transaction.
     * Returns a normalized plan after whole-bucket schedule/qualification checks, without reserving capacity.
     * Maintenance writers must use the same roots; this is neither source authorization nor availability evidence.
     */
    Plan lockAndValidate(Execution execution, List<Demand> demands, List<Qualification> qualifications);

    /**
     * Complete industry policy per staff group: required projects, or a sole explicit exemption with null goods/SKU.
     * Only trusted industry policy may choose an exemption; the later processor must freeze and bind that choice.
     */
    record Qualification(String resourceNo, String sourceLineRef, String goodsNo, String skuNo, boolean required) {
        /** Project-based industries default to requiring the supplied qualification, never an implicit exemption. */
        public Qualification(String resourceNo, String sourceLineRef, String goodsNo, String skuNo) {
            this(resourceNo, sourceLineRef, goodsNo, skuNo, true);
        }
    }
}
