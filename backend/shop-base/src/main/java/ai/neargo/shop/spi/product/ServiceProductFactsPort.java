package ai.neargo.shop.spi.product;

/** Product owns service duration and price; callers may freeze execution facts but never invent a second price. */
public interface ServiceProductFactsPort {
    /**
     * Resolves a saleable SERVICE SKU in an explicit MAIN-tenant CN-market entity/store, or rejects absent/foreign
     * facts.
     */
    Fact get(String entityNo, String storeNo, String skuNo);

    /**
     * Price is a current quote, not a historical price promise or a permission to collect money.
     * Master revisions do not version store/campaign price overlays or promise an atomic quote.
     */
    record Fact(String entityNo, String storeNo, String skuNo, String goodsNo, long priceMinor, int durationMin,
            Long goodsRevision, Long skuRevision) {
        /** Legacy readers lack revision evidence; absence must never be synthesized as version zero. */
        public Fact(String entityNo, String storeNo, String skuNo, String goodsNo, long priceMinor, int durationMin) {
            this(entityNo, storeNo, skuNo, goodsNo, priceMinor, durationMin, null, null);
        }
    }
}
