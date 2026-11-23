package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.pricing.TaxClass;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Actual merchant product policy, independent of prices, activity matches and financial capability. */
public interface GoodsPricingPolicyPort {
    Snapshot resolve(Scope scope, List<SkuRef> skus);
    /**
     * 本门店这些 SKU 已设的课税别（门店 × SKU 的稀疏行）。<b>没设过的 SKU 不出现在结果里</b>，调用方按「待补」处理，
     * 不像 {@link #resolve} 那样整批拒绝 —— 门店零售单的自订 / 待补流程要的就是逐行知道缺哪一个。
     */
    Map<String, TaxClass> taxClasses(Scope scope, Collection<String> skuNos);
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
