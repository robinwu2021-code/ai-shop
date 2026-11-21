package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.OrderEntitlementUseView;
import java.util.List;

/** Application orchestration only; public callers must first resolve session and merchant scope. */
public interface EntitlementOrderCoordinator {
    QuoteView quote(EntitlementContext context, String orderNo, SourceRef source, List<String> selectedInstances);
    OrderEntitlementUseView stage(EntitlementContext context, UseCommand command);
    HoldView prepare(EntitlementContext context, UseCommand command);
    HoldView recover(EntitlementContext context, String entityNo, String operationNo);
}
