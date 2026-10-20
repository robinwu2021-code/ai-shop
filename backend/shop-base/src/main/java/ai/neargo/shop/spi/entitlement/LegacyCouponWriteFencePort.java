package ai.neargo.shop.spi.entitlement;
/** Core-local write barrier shared by the two legacy coupon domains; caller must retain its transaction. */
public interface LegacyCouponWriteFencePort {
    void requireWritable(String tenantNo,EntitlementOwnerRegistryPort.SourceSystem source,String templateNo,boolean originalRelease);
}
