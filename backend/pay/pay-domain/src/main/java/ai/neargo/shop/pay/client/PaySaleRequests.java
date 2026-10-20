package ai.neargo.shop.pay.client;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReleaseSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;
public final class PaySaleRequests {
    private PaySaleRequests() { }
    public record Reserve(EntitlementContext context, ReserveSale command) { }
    public record Release(EntitlementContext context, ReleaseSale command) { }
    public record Query(EntitlementContext context, String entityNo, String reservationNo) { }
}
