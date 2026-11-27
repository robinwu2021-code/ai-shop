package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.CardProductSalePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit sales opt-in; default deployment retains the closed checkout gate.
 *
 * <p>绑定读自 {@code prd_sku.entitlement_*}（门店交易库把 prd_entitlement_binding 并入了 SKU）。
 * 币种取自绑定时冻结的规则快照，与原表的 currency 列同源（写入时已校验二者一致）。
 */
@Component
public class CardProductSalePortImpl implements CardProductSalePort {
    @org.springframework.beans.factory.annotation.Value("${shop.trade.paid-card-sale.enabled:false}")
    private boolean saleEnabled;
    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final ObjectMapper json;
    public CardProductSalePortImpl(GoodsMapper goods, SkuMapper skus, ObjectMapper json) {
        this.goods = goods; this.skus = skus; this.json = json;
    }
    @Override public Optional<SaleDefinition> saleDefinition(String entity,String goodsNo,String sku) {
        return DataScopeContext.executeWithoutScope(() -> {
            var product=goods.selectOne(Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo,entity)
                    .eq(PrdGoods::getGoodsNo,goodsNo));
            if(product==null) throw BizException.of(ErrorCode.NOT_FOUND);
            var bound=bound(product,goodsNo,sku);
            if(bound==null) return Optional.empty();
            if(!PrdGoods.TYPE_CARD.equals(product.getType()) || PrdSku.STATUS_DISABLED.equals(bound.getStatus())
                    || bound.getEntitlementUnits()==null || bound.getEntitlementUnits()<1
                    || bound.getEntitlementRuleSnapshot()==null) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            String currency=json.readTree(bound.getEntitlementRuleSnapshot()).path("currency").asText(null);
            if(currency==null || currency.isBlank()) throw BizException.of(ErrorCode.CONFLICT);
            return Optional.of(new SaleDefinition(bound.getEntitlementVersionNo(),currency,
                    bound.getEntitlementUnits(),bound.getEntitlementRuleSnapshot()));
        });
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
            // A bound card whose SKU is disabled cannot silently become a legacy sale again.
            var bound = bound(product, goodsNo, sku);
            if (bound != null) {
                if (!saleEnabled || PrdSku.STATUS_DISABLED.equals(bound.getStatus())) {
                    throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                }
                saleDefinition(entity,goodsNo,sku).orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
            }
            return null;
        });
    }

    /** 带绑定的那一行 SKU（绑定写在商家本市场那一行；其余市场行没有绑定）。 */
    private PrdSku bound(PrdGoods product, String goodsNo, String sku) {
        return skus.selectOne(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getTenantNo, product.getTenantNo())
                .eq(PrdSku::getGoodsNo, goodsNo).eq(PrdSku::getSkuNo, sku)
                .isNotNull(PrdSku::getEntitlementVersionNo).last("LIMIT 1"));
    }
}
