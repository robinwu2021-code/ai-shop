package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;

@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementUsePort implements EntitlementUsePort {
    private final PayInternalApi pay;
    public RemoteEntitlementUsePort(PayInternalApi pay) { this.pay = pay; }
    @Override public QuoteView quote(EntitlementContext c, QuoteCommand q) {
        return RemoteEntitlementCalls.call(() -> pay.entQuote(new PayEntitlementRequests.UseQuote(c, q)));
    }
    @Override public PreviewView preview(EntitlementContext c, PreviewQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.entPreview(new PayEntitlementRequests.UsePreview(c, q)));
    }
    @Override public HoldView prepare(EntitlementContext c, UseCommand q) {
        return RemoteEntitlementCalls.call(() -> pay.entUsePrepare(new PayEntitlementRequests.UsePrepare(c, q)));
    }
    @Override public UseReceipt commit(EntitlementContext c, UseDecision q) {
        return RemoteEntitlementCalls.call(() -> pay.entUseCommit(new PayEntitlementRequests.UseCommit(c, q)));
    }
    @Override public HoldView release(EntitlementContext c, CancelUse q) {
        return RemoteEntitlementCalls.call(() -> pay.entUseRelease(new PayEntitlementRequests.UseRelease(c, q)));
    }
    @Override public UseReceipt reverse(EntitlementContext c, OrderBenefitReverse q) {
        return RemoteEntitlementCalls.call(() -> pay.entReverse(new PayEntitlementRequests.UseReverse(c, q)));
    }
    @Override public ReversalView queryReverse(EntitlementContext c, String entity, String no) {
        return RemoteEntitlementCalls.call(() ->
                pay.entReverseQuery(new PayEntitlementRequests.ReverseQuery(c, entity, no)));
    }
    @Override public HoldView query(EntitlementContext c, String entity, String no) {
        return RemoteEntitlementCalls.call(() -> pay.entUseQuery(new PayEntitlementRequests.UseQuery(c, entity, no)));
    }
}
