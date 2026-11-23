package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.GoodsPricingPolicyService;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.common.pricing.TaxClass;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Product-owned configuration for every industry; no separate restaurant policy store. */
@Component
public class GoodsPricingPolicyPortImpl implements GoodsPricingPolicyPort {
    private final GoodsPricingPolicyService service;
    public GoodsPricingPolicyPortImpl(GoodsPricingPolicyService service) { this.service = service; }
    @Override public Snapshot resolve(Scope scope, List<SkuRef> skus) { return service.resolve(scope, skus); }
    @Override public Map<String, TaxClass> taxClasses(Scope scope, Collection<String> skuNos) {
        return service.taxClasses(scope, skuNos);
    }
}
