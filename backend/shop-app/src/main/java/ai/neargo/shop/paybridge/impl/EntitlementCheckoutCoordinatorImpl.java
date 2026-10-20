package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementCheckoutCoordinator;
import ai.neargo.shop.paybridge.EntitlementOrderCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementOrderQuoteService;
import ai.neargo.shop.trade.service.EntitlementOrderUseService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public C identity is checked before quoting; no external call runs while core owns a resource lock. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EntitlementCheckoutCoordinatorImpl implements EntitlementCheckoutCoordinator {
    private final EntitlementOrderQuoteService quotes;
    private final EntitlementOrderCoordinator orders;
    private final EntitlementUseSourcePort sources;
    private final EntitlementAuthorizationService authorizations;
    private final EntitlementOrderUseService uses;
    private final PaymentAttemptFactsPort payments;
    public EntitlementCheckoutCoordinatorImpl(EntitlementOrderQuoteService quotes, EntitlementOrderCoordinator orders,
            EntitlementUseSourcePort sources, EntitlementAuthorizationService authorizations,
            EntitlementOrderUseService uses, PaymentAttemptFactsPort payments) {
        this.quotes = quotes; this.orders = orders; this.sources = sources;
        this.authorizations = authorizations; this.uses = uses; this.payments = payments;
    }
    @Override public QuoteView quote(EntitlementContext ctx, String draft, long revision, List<String> instances) {
        if (instances.size() > 1) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var resolved = quotes.resolveDraft(ctx.tenantNo(), ctx.actorNo(), draft, revision);
        var fact = sources.resolve(ctx, resolved.source());
        if (!ctx.actorNo().equals(fact.userNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        var result = orders.quote(ctx, resolved.orderNo(), resolved.source(), instances);
        if (!instances.isEmpty() && result.discountMinor() > 0) {
            quotes.register(ctx, resolved.orderNo(), fact, result, instances.getFirst());
        }
        return result;
    }
    @Override public AuthorizationView confirm(EntitlementContext ctx, String order, AuthorizationCommand c) {
        var selected = quotes.selection(ctx.tenantNo(), ctx.actorNo(), order, c.quoteRef());
        if (selected == null) return authorizations.confirm(ctx.tenantNo(), ctx.actorNo(), order, c);
        String operation = "CU" + EntitlementServiceSnapshotPolicy.hash(c.quoteRef()).substring(0, 60);
        var existing = uses.find(ctx.tenantNo(), selected.source().entityNo(), operation);
        if (existing == null && payments.hasAnyAttempt(ctx.tenantNo(), order)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var prepared = quotes.authorizeAndStage(ctx, order, c);
        var original = prepared.use();
        var result = orders.recover(original.context(), original.command().entityNo(), operation);
        var authorization = prepared.authorization();
        return new AuthorizationView(authorization.authorizationRef(), authorization.status(),
                authorization.expiresAt(), operation, result.status().name());
    }
}
