package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import org.springframework.stereotype.Service;

/** Authoritative source observation never stages, debits, cancels or impersonates the original actor. */
@Service
public class ServiceWorkOrderUseFactsService implements ServiceWorkOrderUseFactsPort {
    private final EntitlementServiceOrderService orders;
    private final EntitlementUseFactsPort pay;
    public ServiceWorkOrderUseFactsService(EntitlementServiceOrderService orders, EntitlementUseFactsPort pay) {
        this.orders = orders;
        this.pay = pay;
    }

    @Override
    public Fact inspect(Lookup lookup) {
        var order = orders.findOperation(lookup.tenantNo(), lookup.entityNo(), lookup.operationNo());
        if (order == null || order.workOrderOrigin() == null || !lookup.storeNo().equals(order.fact().storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        State state = switch (order.status()) {
            case "CREATED", "PREVIEWED" -> State.DRAFT;
            case "AUTHORIZED" -> java.time.Instant.now().isBefore(order.deadline())
                    && order.preview() != null && java.time.Instant.now().isBefore(order.preview().expiresAt())
                    ? State.AUTHORIZED : State.DRAFT;
            case "PENDING", "COMMIT_DECIDED", "COMMITTED" -> State.STAGED;
            case "RELEASED" -> State.RELEASED;
            default -> State.PROCESSING;
        };
        String usage = null;
        if (order.command() != null && state == State.STAGED) {
            var original = pay.query(order.context(), lookup.entityNo(), lookup.operationNo());
            if (original.operation().status() == EntitlementUsePort.State.COMMITTED
                    && lookup.operationNo().equals(original.operation().operationNo())
                    && order.command().equals(original.command())
                    && order.fact().orderSnapshotHash().equals(original.orderSnapshotHash())) {
                state = State.COMMITTED;
                usage = original.operation().usageNo();
            }
        }
        var kind = order.command() == null ? order.preview() == null ? null : order.preview().kind()
                : order.command().assetKind();
        long amount = order.fact().items().stream()
                .mapToLong(EntitlementUsePort.UseItem::amountMinor).reduce(0, Math::addExact);
        return new Fact(lookup, order.context().actorNo(), order.fact().source(), order.workOrderOrigin(),
                order.fact().orderSnapshotHash(), kind, amount, state, usage);
    }
}
