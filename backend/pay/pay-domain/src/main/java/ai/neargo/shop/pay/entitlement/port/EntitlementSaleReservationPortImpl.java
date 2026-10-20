package ai.neargo.shop.pay.entitlement.port;
import ai.neargo.shop.pay.entitlement.service.EntitlementSaleReservationService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
/** Transport adapter; all validation and transactions remain in the pay owner. */
public class EntitlementSaleReservationPortImpl implements EntitlementSaleReservationPort {
    private final EntitlementSaleReservationService service;
    public EntitlementSaleReservationPortImpl(EntitlementSaleReservationService service) { this.service = service; }
    @Override public SaleView reserve(EntitlementContext c, ReserveSale q) { return service.reserve(c,q); }
    @Override public SaleView release(EntitlementContext c, ReleaseSale q) { return service.release(c,q); }
    @Override public SaleView query(EntitlementContext c, String entity, String no) { return service.query(c,entity,no); }
}
