package ai.neargo.shop.product.port;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Resolves ownership in product SQL and delegates price/shelf overlays to the existing product quote owner. */
@Component
public class ServiceProductFactsPortImpl implements ServiceProductFactsPort {
    /**
     * 本市场：SKU 以这个市场的那一行为准。按部署配置 {@code shop.merchant.default-market}（正式环境 TW），
     * 不写死；没配时与 {@code StoreMarkets} 一样回落 CN。手工构造（单测）时也是 CN。
     */
    private String homeMarket = "CN";

    @Value("${shop.merchant.default-market:CN}")
    void setHomeMarket(String market) {
        this.homeMarket = market.trim().toUpperCase(Locale.ROOT);
    }

    private final SkuMapper skus;
    private final GoodsMapper goods;
    private final GoodsQueryPort quotes;
    public ServiceProductFactsPortImpl(SkuMapper skus, GoodsMapper goods, GoodsQueryPort quotes) {
        this.skus = skus;
        this.goods = goods;
        this.quotes = quotes;
    }
    @Override
    public Fact get(String entityNo, String storeNo, String skuNo) {
        if (!valid(entityNo) || !valid(storeNo) || !valid(skuNo)) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return DataScopeContext.executeWithoutScope(() -> {
            var sku = skus.selectOne(Wrappers.<PrdSku>lambdaQuery()
                    .select(PrdSku::getSkuNo, PrdSku::getGoodsNo, PrdSku::getEntityNo, PrdSku::getVersion)
                    .eq(PrdSku::getTenantNo, "MAIN").eq(PrdSku::getEntityNo, entityNo)
                    .eq(PrdSku::getMarket, homeMarket).eq(PrdSku::getSkuNo, skuNo));
            if (sku == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
            var product = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                    .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo,
                            PrdGoods::getType, PrdGoods::getDurationMin, PrdGoods::getVersion)
                    .eq(PrdGoods::getTenantNo, "MAIN").eq(PrdGoods::getEntityNo, entityNo)
                    .eq(PrdGoods::getGoodsNo, sku.getGoodsNo()).eq(PrdGoods::getStoreNo, storeNo));
            if (product == null || !"SERVICE".equals(product.getType())) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            if (product.getDurationMin() == null || product.getDurationMin() < 1) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var quote = quotes.snapshot(List.of(skuNo), Map.of(entityNo, storeNo)).get(skuNo);
            if (quote == null || !quote.onSale()) { throw BizException.of(ErrorCode.NOT_FOUND); }
            if (!entityNo.equals(quote.merchantNo()) || !storeNo.equals(quote.storeNo())
                    || !product.getGoodsNo().equals(quote.goodsNo()) || !skuNo.equals(quote.skuNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return new Fact(entityNo, storeNo, skuNo, product.getGoodsNo(), quote.price(), product.getDurationMin(),
                    product.getVersion(), sku.getVersion());
        });
    }
    private boolean valid(String value) { return value != null && !value.isBlank() && value.length() <= 32; }
}
