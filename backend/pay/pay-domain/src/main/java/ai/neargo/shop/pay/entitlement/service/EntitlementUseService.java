package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseReceipt;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseDecision;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CancelUse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.OrderBenefitReverse;

/** Payment-owned state machine. External source facts are queried before opening pay transactions. */
public interface EntitlementUseService {
    QuoteView quote(EntitlementContext context, QuoteCommand command);
    PreviewView preview(EntitlementContext context, PreviewQuery query);
    HoldView prepare(EntitlementContext context, UseCommand command);
    UseReceipt commit(EntitlementContext context, UseDecision command);
    HoldView release(EntitlementContext context, CancelUse command);
    UseReceipt reverse(EntitlementContext context, OrderBenefitReverse command);
    HoldView query(EntitlementContext context, String entityNo, String operationNo);
}
