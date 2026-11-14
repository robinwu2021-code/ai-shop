package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.Context;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.PolicyVO;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.SaveReq;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Snapshot;
import java.util.List;

public interface GoodsPricingPolicyService {
    PolicyVO get(Context context, String goodsNo, String skuNo);
    PolicyVO save(Context context, String goodsNo, String skuNo, SaveReq request, String idempotencyKey);
    Snapshot resolve(Scope scope, List<SkuRef> skus);
}
