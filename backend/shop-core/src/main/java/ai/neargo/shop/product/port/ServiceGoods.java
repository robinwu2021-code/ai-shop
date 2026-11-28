package ai.neargo.shop.product.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 由「主体 + 门店 + SKU」定位服务商品：服务事实与服务定义共用这一处，归属、门店、类型的判定只写一遍。
 * 调用方负责数据范围（{@code DataScopeContext.executeWithoutScope}）与事务。
 */
@Component
public class ServiceGoods {
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

    public ServiceGoods(SkuMapper skus, GoodsMapper goods) {
        this.skus = skus;
        this.goods = goods;
    }

    /** 服务商品与它的 SKU。 */
    public record Resolved(PrdGoods goods, PrdSku sku) { }

    /**
     * 定位服务商品；{@code lock} 为真时锁住商品行（与商家保存商品同一把锁）。
     * 参数不合法 400；SKU 不属于这个主体、商品不在这家门店或不是服务 404。
     */
    public Resolved resolve(String entityNo, String storeNo, String skuNo, boolean lock) {
        if (!valid(entityNo) || !valid(storeNo) || !valid(skuNo)) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var sku = skus.selectOne(Wrappers.<PrdSku>lambdaQuery()
                .select(PrdSku::getSkuNo, PrdSku::getGoodsNo, PrdSku::getEntityNo, PrdSku::getVersion)
                .eq(PrdSku::getTenantNo, "MAIN").eq(PrdSku::getEntityNo, entityNo)
                .eq(PrdSku::getMarket, homeMarket).eq(PrdSku::getSkuNo, skuNo));
        if (sku == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        var product = goods.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .select(PrdGoods::getGoodsNo, PrdGoods::getEntityNo, PrdGoods::getStoreNo,
                        PrdGoods::getType, PrdGoods::getDurationMin, PrdGoods::getVersion)
                .eq(PrdGoods::getTenantNo, "MAIN").eq(PrdGoods::getEntityNo, entityNo)
                .eq(PrdGoods::getGoodsNo, sku.getGoodsNo()).eq(PrdGoods::getStoreNo, storeNo)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (product == null || !PrdGoods.TYPE_SERVICE.equals(product.getType())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new Resolved(product, sku);
    }

    /** 这件商品在本市场有几个 SKU（按 SKU 发布服务定义时决定写默认行还是覆盖行）。 */
    public long skuCount(String entityNo, String goodsNo) {
        return skus.selectCount(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getTenantNo, "MAIN")
                .eq(PrdSku::getEntityNo, entityNo).eq(PrdSku::getMarket, homeMarket).eq(PrdSku::getGoodsNo, goodsNo));
    }

    private static boolean valid(String value) { return value != null && !value.isBlank() && value.length() <= 32; }
}
