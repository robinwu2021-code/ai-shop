package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.CardProductBindingView;
import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.product.entitlement.CardProductBindingWriter;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.CardProductBindingService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.ReferenceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CardProductBindingServiceImpl implements CardProductBindingService {
    private final CardProductBindingWriter writer;
    private final GoodsMapper goods;
    private final SkuMapper skus;
    private final MerchantScopeFactsPort owners;
    private final MerchantQueryPort merchants;
    private final MarketPort markets;
    private final EntitlementCatalogPort catalog;
    public CardProductBindingServiceImpl(CardProductBindingWriter writer, GoodsMapper goods, SkuMapper skus,
            MerchantScopeFactsPort owners, MerchantQueryPort merchants, MarketPort markets,
            EntitlementCatalogPort catalog) {
        this.writer = writer; this.goods = goods; this.skus = skus; this.owners = owners;
        this.merchants = merchants; this.markets = markets; this.catalog = catalog;
    }
    @Override public CardProductBindingView bind(EntitlementContext ctx, String entity, ProductEntitlementBinding c,
                                                 String key) {
        var replay = writer.replay(ctx, entity, c, key);
        if (replay != null) return replay;
        if (!owners.entityActive(ctx.tenantNo(), entity)) throw BizException.of(ErrorCode.NOT_FOUND);
        var product = DataScopeContext.executeWithoutScope(() -> goods.selectOne(Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getTenantNo, ctx.tenantNo())
                .eq(PrdGoods::getEntityNo, entity).eq(PrdGoods::getGoodsNo, c.goodsNo())));
        if (product == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!PrdGoods.TYPE_CARD.equals(product.getType())) throw BizException.of(ErrorCode.CONFLICT);
        String market = merchants.marketOf(entity);
        var sku = DataScopeContext.executeWithoutScope(() -> skus.selectOne(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getTenantNo, ctx.tenantNo())
                .eq(PrdSku::getEntityNo, entity).eq(PrdSku::getGoodsNo, c.goodsNo()).eq(PrdSku::getSkuNo, c.skuNo())
                .eq(PrdSku::getMarket, market)));
        if (sku == null || sku.getPrice() == null || sku.getPrice() <= 0) throw BizException.of(ErrorCode.NOT_FOUND);
        var currency = markets.find(market).filter(MarketPort.MarketBrief::enabled)
                .orElseThrow(() -> BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).currency();
        var definition = catalog.validateReference(new ReferenceQuery(ctx.tenantNo(), entity, c.templateVersionNo(), currency,
                "PAID_PRODUCT"));
        return writer.write(ctx, entity, c, key, market, currency, definition);
    }
}
