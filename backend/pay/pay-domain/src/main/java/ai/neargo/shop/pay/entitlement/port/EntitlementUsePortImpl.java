package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;

/** Domain adapter; HTTP/user scope construction remains in main application. */
public class EntitlementUsePortImpl implements EntitlementUsePort {
    private final EntitlementUseService service;
    public EntitlementUsePortImpl(EntitlementUseService service) { this.service = service; }
    @Override
    public QuoteView quote(EntitlementContext c, QuoteCommand q) {
        return service.quote(c, q);
    }
    @Override
    public PreviewView preview(EntitlementContext c, PreviewQuery q) {
        return service.preview(c, q);
    }
    @Override
    public HoldView prepare(EntitlementContext c, UseCommand q) {
        return service.prepare(c, q);
    }
    @Override
    public UseReceipt commit(EntitlementContext c, UseDecision q) {
        return service.commit(c, q);
    }
    @Override
    public HoldView release(EntitlementContext c, CancelUse q) {
        return service.release(c, q);
    }
    @Override
    public UseReceipt reverse(EntitlementContext c, OrderBenefitReverse q) {
        return service.reverse(c, q);
    }
    @Override
    public ReversalView queryReverse(EntitlementContext c, String entity, String reverseNo) {
        return service.queryReverse(c, entity, reverseNo);
    }
    @Override
    public HoldView query(EntitlementContext c, String e, String n) {
        return service.query(c, e, n);
    }
}
