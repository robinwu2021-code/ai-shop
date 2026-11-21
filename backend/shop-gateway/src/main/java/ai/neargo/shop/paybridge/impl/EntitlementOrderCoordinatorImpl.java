package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementOrderCoordinator;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CancelUse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseDecision;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.OrderEntitlementUseView;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementOrderUseService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every owner call commits independently. Persist the original command before any potentially lost pay response. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EntitlementOrderCoordinatorImpl implements EntitlementOrderCoordinator {
    private final EntitlementUseSourcePort sources;
    private final EntitlementUsePort pay;
    private final EntitlementUseFactsPort facts;
    private final PaymentAttemptFactsPort attempts;
    private final EntitlementAuthorizationService authorizations;
    private final EntitlementOrderUseService orders;
    private final EntitlementServiceUseCoordinator serviceUses;
    public EntitlementOrderCoordinatorImpl(EntitlementUseSourcePort sources, EntitlementUsePort pay,
            EntitlementUseFactsPort facts, PaymentAttemptFactsPort attempts,
            EntitlementAuthorizationService authorizations, EntitlementOrderUseService orders,
            EntitlementServiceUseCoordinator serviceUses) {
        this.sources = sources; this.pay = pay; this.facts = facts; this.attempts = attempts;
        this.authorizations = authorizations; this.orders = orders; this.serviceUses = serviceUses;
    }
    @Override public QuoteView quote(EntitlementContext ctx, String orderNo, SourceRef source, List<String> instances) {
        var fact = sources.resolve(ctx, source);
        long goods = 0;
        for (var line : fact.items()) goods = Math.addExact(goods, line.amountMinor());
        var result = pay.quote(ctx, new QuoteCommand(source, fact.userNo(), fact.entityNo(), fact.storeNo(),
                fact.currency(), goods, fact.freightMinor(), fact.items(), instances, fact.orderSnapshotHash()));
        authorizations.stageTrustedIntent(ctx.tenantNo(), orderNo, source, result.quoteRef(), result.selectionDigest(),
                result.discountMinor(), fact.currency(), result.expiresAt());
        return result;
    }
    @Override public OrderEntitlementUseView stage(EntitlementContext ctx, UseCommand command) {
        var prior = orders.find(ctx.tenantNo(), command.entityNo(), command.operationNo());
        if (prior == null) {
            // Guard old charge rows created before the price seal existed, even if their callbacks never arrived.
            var fact = sources.resolve(ctx, command.sourceRef());
            if (!fact.userNo().equals(command.userNo()) || !fact.entityNo().equals(command.entityNo())) conflict();
            // Authorization staging already bound the suborder to its main order; ask core for that immutable fact.
            String orderNo = orders.orderNoForAuthorization(ctx.tenantNo(), command.authorizationRef());
            if (attempts.hasAnyAttempt(ctx.tenantNo(), orderNo)) conflict();
        }
        return orders.stage(ctx, command);
    }
    @Override public HoldView prepare(EntitlementContext ctx, UseCommand command) {
        var row = stage(ctx, command);
        if (row.decisionRef() != null) return recover(ctx, command.entityNo(), command.operationNo());
        try {
            var result = pay.prepare(row.context(), row.command());
            if (result.status() == State.HELD || result.status() == State.COMMITTED) {
                var held = facts.query(row.context(), command.entityNo(), command.operationNo());
                if (held.operation().status() != State.HELD && held.operation().status() != State.COMMITTED) {
                    return processing(command.operationNo());
                }
                orders.apply(row.context(), held);
            }
            return result;
        } catch (BizException failure) {
            if (definitive(failure)) throw failure;
            return processing(command.operationNo());
        }
    }
    @Override public HoldView recover(EntitlementContext ctx, String entity, String operation) {
        var row = orders.find(ctx.tenantNo(), entity, operation);
        if (row == null) return serviceUses.recover(ctx, entity, operation);
        orders.decideForTerminalOrder(ctx.tenantNo(), row.orderNo());
        row = orders.find(ctx.tenantNo(), entity, operation);
        if (row.decisionRef() == null && "PENDING".equals(row.status())) return prepare(row.context(), row.command());
        try {
            if (row.decisionRef() == null) return pay.query(row.context(), entity, operation);
            if ("CANCEL_DECIDED".equals(row.status()) || "RELEASED".equals(row.status())) {
                var result = pay.release(row.context(),
                        new CancelUse(operation, row.command().sourceRef(), row.decisionRef()));
                orders.acknowledge(row.context(), entity, result); return result;
            }
            pay.commit(row.context(), new UseDecision(operation, row.command().sourceRef(), row.decisionRef()));
            var result = pay.query(row.context(), entity, operation);
            orders.acknowledge(row.context(), entity, result); return result;
        } catch (BizException failure) {
            if (definitive(failure)) throw failure;
            return processing(operation);
        }
    }
    private static boolean definitive(BizException failure) {
        return switch (failure.errorCode()) {
            case BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT -> true;
            default -> false;
        };
    }
    private static HoldView processing(String operation) {
        return new HoldView(operation, State.PROCESSING, null, null, null);
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
