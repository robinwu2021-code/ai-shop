package ai.neargo.commerce.api.catalog;

/** Master and version optimistic-lock tokens are distinct. */
public record SkuDefinitionView(
        String skuNo,
        String productNo,
        String title,
        int maxPerOrder,
        String currentVersionNo,
        String saleStatus,
        long version) {}
