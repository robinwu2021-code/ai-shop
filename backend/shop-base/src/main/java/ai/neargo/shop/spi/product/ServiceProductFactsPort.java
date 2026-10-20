package ai.neargo.shop.spi.product;

/** Product owns service duration and price; callers may freeze execution facts but never invent a second price. */
public interface ServiceProductFactsPort {
    /**
     * Resolves a saleable SERVICE SKU in an explicit MAIN-tenant CN-market entity/store, or rejects absent/foreign
     * facts.
     */
    Fact get(String entityNo, String storeNo, String skuNo);

    /** Price is a current quote, not a historical price promise or a permission to collect money. */
    record Fact(String entityNo, String storeNo, String skuNo, String goodsNo, long priceMinor, int durationMin) { }
}
