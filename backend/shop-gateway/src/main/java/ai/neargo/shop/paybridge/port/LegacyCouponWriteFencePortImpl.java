package ai.neargo.shop.paybridge.port;
import ai.neargo.shop.spi.entitlement.LegacyCouponWriteFencePort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** Thin Core-local adapter: no Pay call can occur while the original legacy root is locked. */
@Component
public class LegacyCouponWriteFencePortImpl implements LegacyCouponWriteFencePort {
    private final LegacyCouponSourceFenceService owner;
    public LegacyCouponWriteFencePortImpl(LegacyCouponSourceFenceService owner) {this.owner=owner;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireWritable(String tenant,SourceSystem source,String no,boolean release) {owner.requireWritable(tenant,source,no,release);}
}
