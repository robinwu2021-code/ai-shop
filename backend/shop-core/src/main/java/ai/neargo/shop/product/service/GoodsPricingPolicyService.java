package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.Context;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.PolicyVO;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.SaveReq;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Snapshot;
import ai.neargo.shop.common.pricing.TaxClass;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface GoodsPricingPolicyService {
    PolicyVO get(Context context, String goodsNo, String skuNo);
    PolicyVO save(Context context, String goodsNo, String skuNo, SaveReq request, String idempotencyKey);
    Snapshot resolve(Scope scope, List<SkuRef> skus);
    /** 门店 × SKU 已设的课税别；没设的 SKU 不出现。 */
    Map<String, TaxClass> taxClasses(Scope scope, Collection<String> skuNos);
}
