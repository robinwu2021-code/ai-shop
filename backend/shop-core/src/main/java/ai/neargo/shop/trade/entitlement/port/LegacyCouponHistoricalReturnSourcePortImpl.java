package ai.neargo.shop.trade.entitlement.port;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.trade.service.LegacyCouponHistoricalReturnService;
import org.springframework.stereotype.Component;
/** Narrow read/ack boundary, independent of the core return producer bean. */
@Component
public class LegacyCouponHistoricalReturnSourcePortImpl implements LegacyCouponHistoricalReturnSourcePort {
    private final LegacyCouponHistoricalReturnService owner;
    public LegacyCouponHistoricalReturnSourcePortImpl(LegacyCouponHistoricalReturnService owner) {this.owner=owner;}
    @Override public Fact resolve(EntitlementContext ctx,String no) {return owner.resolve(ctx,no);}
    @Override public void acknowledge(EntitlementContext ctx,LegacyCouponHistoricalReturnPort.Receipt receipt) {owner.acknowledge(ctx,receipt);}
}
