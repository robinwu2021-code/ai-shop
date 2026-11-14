package ai.neargo.shop.spi.platform;

/** Platform-owned regional contact policy shared by operator admission paths. */
public interface MarketContactPolicyPort {
    interface Policy {
        String market();
        boolean recordOnly();
        boolean marketEnabled();
    }
    Policy instancePolicy();
    Policy validateContact(String market, String phone);
}
