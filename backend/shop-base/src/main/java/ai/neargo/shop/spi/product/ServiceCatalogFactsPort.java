package ai.neargo.shop.spi.product;

/** Public catalog projection of existing merchant/product authorities; never a reservation or payment permit. */
public interface ServiceCatalogFactsPort {
    /** Resolves a public store through the existing directory, without trusting a client-supplied entity. */
    Store store(String storeNo);
    /** Resolves a saleable service with its live store quote and display facts under the resolved owner. */
    Service service(String storeNo, String skuNo);

    record Store(String entityNo, String storeNo, String name, String logo, String status,
                 String openHours, String address, StoreSalesCatalogPort.Snapshot catalog) { }
    /** Master revisions do not version store/campaign price overlays or promise an atomic quote. */
    record Service(Store store, String skuNo, String goodsNo, String title, String cover,
                   long priceMinor, int durationMin, Long goodsRevision, Long skuRevision) {
        /** Existing catalog readers remain compatible; booking requires observed master revisions. */
        public Service(Store store, String skuNo, String goodsNo, String title, String cover,
                long priceMinor, int durationMin) {
            this(store, skuNo, goodsNo, title, cover, priceMinor, durationMin, null, null);
        }
    }
}
