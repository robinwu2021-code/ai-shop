package ai.neargo.shop.food.spi;

/** Real channel-account/price-policy authority. No default success implementation is provided. */
public interface MenuChannelAuthorityPort {
    boolean authorized(String entityNo, String storeNo, String provider, String accountRef, String externalStoreRef);
    boolean pricePolicyAuthorized(String entityNo, String storeNo, String bindingNo, String pricePolicyRef);
}
