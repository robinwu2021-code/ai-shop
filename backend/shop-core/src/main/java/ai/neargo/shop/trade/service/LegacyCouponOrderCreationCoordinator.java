package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** App orchestration boundary. The core order transaction begins only after a pay hold is established. */
public interface LegacyCouponOrderCreationCoordinator {
    LegacyCouponUsePort.Operation recover(
            EntitlementContext ctx,String checkoutNo);
    OrderVO createFor(String userNo,OrderService.CreateOrderCommand command,String idempotencyKey,Integer payMinutes);
}
