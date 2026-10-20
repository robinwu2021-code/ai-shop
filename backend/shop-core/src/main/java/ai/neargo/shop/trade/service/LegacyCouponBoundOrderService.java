package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import ai.neargo.shop.trade.dto.OrderVO;

/** Internal core create entry accepting a pre-existing pay hold. It never performs pay RPC in the order transaction. */
public interface LegacyCouponBoundOrderService {
    record Binding(EntitlementContext context,String checkoutNo,String commandHash,LegacyCouponUsePort.Operation held) {
        public Binding {ContractChecks.present(context);ContractChecks.id(checkoutNo);ContractChecks.hash(commandHash);ContractChecks.present(held);}
    }
    OrderVO createCoreFor(String userNo,OrderService.CreateOrderCommand command,String idempotencyKey,Integer payMinutes);
    OrderVO readCreated(EntitlementContext ctx,String checkoutNo,String userNo);
    OrderVO createBound(String userNo,OrderService.CreateOrderCommand command,String idempotencyKey,
                        Integer payMinutes,Binding binding);
}
