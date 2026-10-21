package ai.neargo.shop.spi.product;

/** Public catalog projection of existing merchant/product authorities; never a reservation or payment permit. */
public interface ServiceCatalogFactsPort {
    /** Resolves a public store through the existing directory, without trusting a client-supplied entity. */
    Store store(String storeNo);
    /** Resolves a saleable service with its live store quote and display facts under the resolved owner. */
    Service service(String storeNo, String skuNo);

    record Store(String entityNo, String storeNo, String name, String logo, String status,
                 String openHours, String address, StoreSalesCatalogPort.Snapshot catalog) { }
    record Service(Store store, String skuNo, String goodsNo, String title, String cover,
                   long priceMinor, int durationMin) { }
}
