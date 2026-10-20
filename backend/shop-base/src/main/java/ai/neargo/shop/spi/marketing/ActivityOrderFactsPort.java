package ai.neargo.shop.spi.marketing;

/** Trusted persisted order facts used when committing or returning marketing capacity. */
public interface ActivityOrderFactsPort {
    Fact find(String tenantNo, String orderNo, String entityNo, String storeNo);
    record Fact(String subOrderNo, String userNo, String status, long goodsAmountMinor, int goodsQuantity) { }
}
