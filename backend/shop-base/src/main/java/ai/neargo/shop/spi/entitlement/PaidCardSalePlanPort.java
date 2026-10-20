package ai.neargo.shop.spi.entitlement;
import java.util.List;
/** Internal source-owner plan; every unit is still checked against current durable source truth. */
public interface PaidCardSalePlanPort {
    List<SaleUnit> units(EntitlementContext context, String orderNo);
    record SaleUnit(EntitlementSaleReservationPort.ReserveSale reservation, String paidFactRef) { }
}
