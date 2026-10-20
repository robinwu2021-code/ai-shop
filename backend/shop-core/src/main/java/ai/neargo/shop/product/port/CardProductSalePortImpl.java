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
import java.util.Optional;

/** Explicit sales opt-in; default deployment retains the closed checkout gate. */
@Component
public class CardProductSalePortImpl implements CardProductSalePort {
    @org.springframework.beans.factory.annotation.Value("${shop.trade.paid-card-sale.enabled:false}")
    private boolean saleEnabled;
    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final PrdEntitlementBindingMapper bindings;
    public CardProductSalePortImpl(GoodsMapper goods, SkuMapper skus, PrdEntitlementBindingMapper bindings) {
        this.goods = goods; this.skus = skus; this.bindings = bindings;
    }
    @Override public Optional<SaleDefinition> saleDefinition(String entity,String goodsNo,String sku) {
        return DataScopeContext.executeWithoutScope(() -> {
            var product=goods.selectOne(Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getEntityNo,entity)
                    .eq(PrdGoods::getGoodsNo,goodsNo));
            if(product==null) throw BizException.of(ErrorCode.NOT_FOUND);
            var binding=bindings.findIdentity(product.getTenantNo(),goodsNo,sku);
            if(binding==null) return Optional.empty();
            if(!PrdGoods.TYPE_CARD.equals(product.getType())
                    || !"ACTIVE".equals(binding.getStatus()) || Integer.valueOf(1).equals(binding.getDeleted())
                    || binding.getUnitsPerItem()==null || binding.getUnitsPerItem()<1
                    || binding.getRuleSnapshot()==null || binding.getTemplateVersionNo()==null) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return Optional.of(new SaleDefinition(binding.getTemplateVersionNo(),binding.getCurrency(),
                    binding.getUnitsPerItem(),binding.getRuleSnapshot()));
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
            // Disabled or deleted new bindings cannot silently become legacy sales again.
            var binding = bindings.findIdentity(product.getTenantNo(), goodsNo, sku);
            if (binding != null) {
                if (!saleEnabled || !"ACTIVE".equals(binding.getStatus()) || Integer.valueOf(1).equals(binding.getDeleted())) {
                    throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                }
                saleDefinition(entity,goodsNo,sku).orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
            }
            return null;
        });
    }
}
