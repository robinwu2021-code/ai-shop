package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.dto.PreparedGrant;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReleaseSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.SaleView;

/** Pay owner service; application/remote adapters bind the public SPI separately. */
public interface EntitlementSaleReservationService {
    SaleView reserve(EntitlementContext context, ReserveSale command);
    SaleView release(EntitlementContext context, ReleaseSale command);
    SaleView query(EntitlementContext context, String entityNo, String reservationNo);
    PreparedGrant promise(EntitlementContext context, GrantCommand command);
}
