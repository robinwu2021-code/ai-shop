package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import ai.neargo.shop.trade.dto.OrderCouponSelection;
import ai.neargo.shop.trade.dto.OrderEntitlementUseView;

public interface EntitlementOrderQuoteService {
    record Draft(String orderNo, SourceRef source) { }
    record Prepared(AuthorizationView authorization, OrderEntitlementUseView use) { }
    Draft resolveDraft(String tenantNo, String userNo, String draftRef, long revision);
    void register(EntitlementContext context, String orderNo, UseSourceFact source, QuoteView quote, String instanceNo);
    OrderCouponSelection selection(String tenantNo, String userNo, String orderNo, String quoteRef);
    Prepared authorizeAndStage(EntitlementContext context, String orderNo, AuthorizationCommand command);
}
