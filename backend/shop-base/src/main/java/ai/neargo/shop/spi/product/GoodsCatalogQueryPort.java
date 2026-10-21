package ai.neargo.shop.spi.product;

import java.util.List;

/** Store-owned management metadata. These facts grant no price, stock or permission to transact. */
public interface GoodsCatalogQueryPort {
    /** Bounded management lookup; unknown or unauthorized identities are omitted, service failures are not. */
    List<GoodsSummary> getGoodsBatch(String entityNo, String storeNo, List<String> goodsNos);

    /** Bounded list metadata deliberately omits SKU expansion, price and inventory. */
    record GoodsSummary(String goodsNo, String entityNo, String storeNo, String title, String cover,
            boolean baseOnSale, String auditStatus, long version) { }

    GoodsFact getGoods(String entityNo, String storeNo, String goodsNo);

    record GoodsFact(String goodsNo, String entityNo, String storeNo, String title, String cover,
            boolean baseOnSale, String auditStatus, long version, List<SkuFact> skus) {
        public GoodsFact { skus = List.copyOf(skus); }
    }

    record SkuFact(String skuNo, String spec, long version) { }
}
