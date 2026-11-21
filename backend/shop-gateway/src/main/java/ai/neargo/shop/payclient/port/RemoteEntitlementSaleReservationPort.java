package ai.neargo.shop.payclient.port;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PaySaleRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="shop.pay.deployment", havingValue="standalone")
public class RemoteEntitlementSaleReservationPort implements EntitlementSaleReservationPort {
    private final PayInternalApi pay;
    public RemoteEntitlementSaleReservationPort(PayInternalApi pay) { this.pay=pay; }
    @Override public SaleView reserve(EntitlementContext c, ReserveSale q) {
        return RemoteEntitlementCalls.call(() -> pay.saleReserve(new PaySaleRequests.Reserve(c,q)));
    }
    @Override public SaleView release(EntitlementContext c, ReleaseSale q) {
        return RemoteEntitlementCalls.call(() -> pay.saleRelease(new PaySaleRequests.Release(c,q)));
    }
    @Override public SaleView query(EntitlementContext c, String entity, String no) {
        return RemoteEntitlementCalls.call(() -> pay.saleQuery(new PaySaleRequests.Query(c,entity,no)));
    }
}
