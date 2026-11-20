package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import java.util.List;

public interface EntitlementCheckoutCoordinator {
    QuoteView quote(EntitlementContext context, String draftRef, long revision, List<String> instances);
    AuthorizationView confirm(EntitlementContext context, String orderNo, AuthorizationCommand command);
}
