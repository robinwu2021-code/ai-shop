package ai.neargo.shop.pay.entitlement.port;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort;
/** Embedded owner adapter; standalone transport exposes the same service contract. */
public final class LegacyCouponHistoricalReturnPortImpl implements LegacyCouponHistoricalReturnPort {
    private final LegacyCouponHistoricalReturnService owner;
    public LegacyCouponHistoricalReturnPortImpl(LegacyCouponHistoricalReturnService owner) {this.owner=owner;}
    public Receipt reverse(EntitlementContext context,String entity,String no) {return owner.reverse(context,entity,no);}
    public Receipt query(EntitlementContext context,String entity,String no) {return owner.query(context,entity,no);}
}
