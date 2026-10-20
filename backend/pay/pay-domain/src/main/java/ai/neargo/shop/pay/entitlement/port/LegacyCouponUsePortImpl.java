package ai.neargo.shop.pay.entitlement.port;
import ai.neargo.shop.pay.migration.service.LegacyCouponUseService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;

/** Embedded adapter only; standalone callers use the authenticated pay service. */
public class LegacyCouponUsePortImpl implements LegacyCouponUsePort {
    private final LegacyCouponUseService service;
    public LegacyCouponUsePortImpl(LegacyCouponUseService service){this.service=service;}
    public Snapshot snapshot(EntitlementContext ctx,Owned c){return service.snapshot(ctx,c);}
    public Estimate estimate(EntitlementContext ctx,EstimateCommand c){return service.estimate(ctx,c);}
    public Quote quote(EntitlementContext ctx,Prepare c){return service.quote(ctx,c);}
    public Operation prepare(EntitlementContext ctx,Prepare c){return service.prepare(ctx,c);}
    public Operation commit(EntitlementContext ctx,Complete c){return service.commit(ctx,c);}
    public Operation release(EntitlementContext ctx,Complete c){return service.release(ctx,c);}
    public Operation reverse(EntitlementContext ctx,Complete c){return service.reverse(ctx,c);}
    public Operation query(EntitlementContext ctx,String entity,String no){return service.query(ctx,entity,no);}
}
