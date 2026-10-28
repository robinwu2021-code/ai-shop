package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsSummary;
import java.util.List;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Shared product metadata wire contract, with no restaurant runtime dependency. */
public interface GoodsCatalogInternalApi {
    String MARKET_GOODS_PATH = "/internal/product/catalog/goods/market-lookup";
    String MARKET_SKU_PATH = "/internal/product/catalog/goods/market-sku";

    /** Actual market is original evidence, never a caller-selected query filter. */
    @PostExchange(MARKET_GOODS_PATH)
    CatalogMarketContracts.GoodsFacts lookupMarketGoods(@RequestBody CatalogMarketContracts.GoodsQuery query);

    /** Original SKU and parent versions remain scoped to the original store and actual market. */
    @PostExchange(MARKET_SKU_PATH)
    CatalogMarketContracts.SkuFacts lookupMarketSku(@RequestBody CatalogMarketContracts.SkuQuery query);

    String IMAGES_PATH = "/internal/product/catalog-images/resolve";

    /** Strict original cover identity travels without public-response rewriting. */
    @PostExchange(IMAGES_PATH)
    CatalogImageContracts.Facts resolveImages(@RequestBody CatalogImageContracts.Query query);

    String SKU_PATH = "/internal/product/catalog/goods/sku";
    @PostExchange(SKU_PATH)
    GoodsCatalogQueryPort.SkuIdentityFact lookupSku(@RequestBody SkuLookup request);

    record SkuLookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotBlank @Size(max=32) String skuNo) { }

    String LOOKUP_PATH = "/internal/product/catalog/goods/lookup";

    @PostExchange(LOOKUP_PATH)
    GoodsFact lookup(@RequestBody Lookup request);

    String BATCH_PATH = "/internal/product/catalog/goods/batch";

    @PostExchange(BATCH_PATH)
    List<GoodsSummary> lookupBatch(@RequestBody BatchLookup request);

    record BatchLookup(@NotBlank @Size(max=32) String entityNo, @NotBlank @Size(max=32) String storeNo,
            @NotNull @Size(min=1,max=256) List<@NotBlank @Size(max=32) String> goodsNos) { }

    record Lookup(@NotBlank @Size(max = 32) String entityNo,
            @NotBlank @Size(max = 32) String storeNo,
            @NotBlank @Size(max = 32) String goodsNo) { }
}
