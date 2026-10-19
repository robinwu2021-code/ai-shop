package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.trade.dto.OrderEntitlementUseView;
import ai.neargo.shop.trade.entity.OrdOrder;

/** Core transactions only. External pay calls belong in the application coordinator. */
public interface EntitlementOrderUseService {
    String orderNoForAuthorization(String tenantNo, String authorizationRef);
    OrderEntitlementUseView find(String tenantNo, String entityNo, String operationNo);
    OrderEntitlementUseView stage(EntitlementContext context, UseCommand command);
    void apply(EntitlementContext context, UseFact fact);
    OrdOrder sealForPayment(String tenantNo, String orderNo);
    void decideForTerminalOrder(String tenantNo, String orderNo);
    void acknowledge(EntitlementContext context, String entityNo, HoldView result);
}
