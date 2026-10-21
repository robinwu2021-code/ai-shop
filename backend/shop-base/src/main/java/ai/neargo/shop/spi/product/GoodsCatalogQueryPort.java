package ai.neargo.shop.spi.product;

import java.util.List;

/** Store-owned management metadata. These facts grant no price, stock or permission to transact. */
public interface GoodsCatalogQueryPort {
    GoodsFact getGoods(String entityNo, String storeNo, String goodsNo);

    record GoodsFact(String goodsNo, String entityNo, String storeNo, String title, String cover,
            boolean baseOnSale, String auditStatus, long version, List<SkuFact> skus) {
        public GoodsFact { skus = List.copyOf(skus); }
    }

    record SkuFact(String skuNo, String spec, long version) { }
}
