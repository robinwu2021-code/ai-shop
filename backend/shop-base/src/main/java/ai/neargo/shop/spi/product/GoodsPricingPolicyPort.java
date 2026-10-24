package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.pricing.TaxClass;
import java.util.List;

/** Actual merchant product policy, independent of prices, activity matches and financial capability. */
public interface GoodsPricingPolicyPort {
    Snapshot resolve(Scope scope, List<SkuRef> skus);
    record Scope(String tenantNo, String entityNo, String storeNo) {
        public Scope {
            GoodsPricingPolicyFacts.key(tenantNo); GoodsPricingPolicyFacts.key(entityNo);
            GoodsPricingPolicyFacts.key(storeNo);
            if (!"MAIN".equals(tenantNo)) throw new IllegalArgumentException("Existing MAIN tenant required");
        }
    }
    record SkuRef(String goodsNo, String skuNo) {
        public SkuRef { GoodsPricingPolicyFacts.key(goodsNo); GoodsPricingPolicyFacts.key(skuNo); }
    }
    record Policy(String policyNo, String goodsNo, String skuNo, long version,
            boolean promotionEligible, TaxClass taxClass) { }
    record Snapshot(int schemaVersion, Scope scope, List<Policy> policies, String snapshotHash) {
        public Snapshot { policies = policies == null ? null : List.copyOf(policies); }
    }
}
