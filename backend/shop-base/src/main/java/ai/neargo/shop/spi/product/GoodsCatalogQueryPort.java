package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Store-owned management metadata. These facts grant no price, stock or permission to transact. */
public interface GoodsCatalogQueryPort {
    /** Bounded management lookup; unknown or unauthorized identities are omitted, service failures are not. */
    List<GoodsSummary> getGoodsBatch(String entityNo, String storeNo, List<String> goodsNos);

    /** Bounded list metadata deliberately omits SKU expansion, price and inventory. */
    record GoodsSummary(String goodsNo, String entityNo, String storeNo, String title, String cover,
            boolean baseOnSale, String auditStatus, long version) { }

    /** Pure identity lookup for configuration; optional older providers must fail closed, never invent facts. */
    default SkuIdentityFact getSku(String entityNo, String storeNo, String skuNo) {
        throw new UnsupportedOperationException("SKU identity lookup is unavailable");
    }

    record SkuIdentityFact(String skuNo, String goodsNo, String entityNo, String storeNo,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version) { }

    /** Original actual market is resolved before metadata; old owners cannot silently return CN facts. */
    default CatalogMarketContracts.GoodsFacts getCatalogGoods(CatalogMarketContracts.GoodsQuery query) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    /** Configuration identity lookup is independent of sellability and has no cross-market fallback. */
    default CatalogMarketContracts.SkuFacts getCatalogSku(CatalogMarketContracts.SkuQuery query) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    /** Original cover proof is optional on older owners; never treat its absence as no image. */
    default CatalogImageContracts.Facts resolveCatalogImages(CatalogImageContracts.Query query) {
        throw new UnsupportedOperationException("Catalog image identity lookup is unavailable");
    }

    GoodsFact getGoods(String entityNo, String storeNo, String goodsNo);

    record GoodsFact(String goodsNo, String entityNo, String storeNo, String title, String cover,
            boolean baseOnSale, String auditStatus, long version, List<SkuFact> skus) {
        public GoodsFact { skus = List.copyOf(skus); }
    }

    record SkuFact(String skuNo, String spec, long version) { }
}
