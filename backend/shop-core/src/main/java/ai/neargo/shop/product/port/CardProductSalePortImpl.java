package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.PrdEntitlementBindingMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.CardProductSalePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** New paid-card bindings remain unsellable until their actual prepayment/issuance owner is implemented. */
@Component
public class CardProductSalePortImpl implements CardProductSalePort {
    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final PrdEntitlementBindingMapper bindings;
    public CardProductSalePortImpl(GoodsMapper goods, SkuMapper skus, PrdEntitlementBindingMapper bindings) {
        this.goods = goods; this.skus = skus; this.bindings = bindings;
    }
    @Override public void requirePurchasable(String entity, String goodsNo, String sku) {
        DataScopeContext.executeWithoutScope(() -> {
            // goods_no is globally unique public-catalog identity; derive tenant from its authoritative owner row.
            var product = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo, entity)
                    .eq(PrdGoods::getGoodsNo, goodsNo));
            if (product == null) throw BizException.of(ErrorCode.NOT_FOUND);
            if (!PrdGoods.TYPE_CARD.equals(product.getType())) throw BizException.of(ErrorCode.CONFLICT);
            long matches = skus.selectCount(Wrappers.<PrdSku>lambdaQuery()
                    .eq(PrdSku::getTenantNo, product.getTenantNo()).eq(PrdSku::getEntityNo, entity)
                    .eq(PrdSku::getGoodsNo, goodsNo).eq(PrdSku::getSkuNo, sku));
            if (matches == 0) throw BizException.of(ErrorCode.NOT_FOUND);
            // Disabled or deleted new bindings cannot silently become legacy sales again.
            var binding = bindings.findIdentity(product.getTenantNo(), goodsNo, sku);
            if (binding != null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return null;
        });
    }
}
