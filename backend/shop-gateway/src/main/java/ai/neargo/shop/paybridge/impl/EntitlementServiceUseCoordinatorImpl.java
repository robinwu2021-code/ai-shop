package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CancelUse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseDecision;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** No core resource lock crosses a pay call. The same operation, original service day and decision survive retries. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EntitlementServiceUseCoordinatorImpl implements EntitlementServiceUseCoordinator {
    private final EntitlementServiceOrderService orders;
    private final EntitlementQueryPort queries;
    private final EntitlementUsePort pay;
    private final EntitlementUseFactsPort facts;
    public EntitlementServiceUseCoordinatorImpl(EntitlementServiceOrderService orders, EntitlementQueryPort queries,
                                               EntitlementUsePort pay, EntitlementUseFactsPort facts) {
        this.orders = orders; this.queries = queries; this.pay = pay; this.facts = facts;
    }
    @Override public PreviewView preview(EntitlementContext ctx, String entity, String store, String no,
                                         String instance,
                                         LocalDate requestedDay) {
        var order = orders.requireMerchant(ctx, entity, store, no);
        if (order.preview() != null && order.preview().kind() == AssetKind.STORED_VALUE) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (order.preview() != null && Instant.now().isBefore(order.preview().preview().expiresAt())) {
            if (!instance.equals(order.preview().preview().instanceNo())) throw BizException.of(ErrorCode.CONFLICT);
            if (requestedDay != null && !requestedDay.equals(order.preview().serviceDay())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return order.preview().preview();
        }
        if (!List.of("CREATED", "PREVIEWED").contains(order.status()) || !Instant.now().isBefore(order.deadline())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var detail = queries.detail(ctx, new DetailQuery(order.fact().userNo(), entity, instance, store));
        AssetKind kind = detail.entitlement().kind();
        if (!List.of(AssetKind.PAID_CARD, AssetKind.STORE_EXCHANGE).contains(kind)) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        var allowance = queries.allowances(ctx, new AllowanceQuery(order.fact().userNo(), entity, instance, null));
        var day = order.fact().serviceAt().atZone(ZoneId.of(allowance.zone())).toLocalDate();
        if (requestedDay != null && !requestedDay.equals(day)) throw BizException.of(ErrorCode.BAD_REQUEST);
        int units = order.fact().items().stream().mapToInt(i -> i.quantity()).reduce(0, Math::addExact);
        var preview = pay.preview(ctx, new PreviewQuery(order.fact().userNo(), entity, store, order.fact().currency(),
                instance, day, units, order.fact().source()));
        Instant expiry = preview.expiresAt().isBefore(order.deadline()) ? preview.expiresAt() : order.deadline();
        var assetExpiry = detail.entitlement().effectiveUntil();
        if (assetExpiry != null && assetExpiry.isBefore(expiry)) expiry = assetExpiry;
        preview = new PreviewView(preview.previewRef(), preview.instanceNo(), preview.title(), preview.maskedHolder(),
                preview.redeemable(), preview.reason(), preview.unitsToUse(), preview.remainingAfter(),
                preview.assetRevision(), preview.snapshotHash(), expiry);
        if (preview.redeemable()) orders.bindPreview(ctx, entity, store, no, kind, preview, day);
        return preview;
    }
    @Override public HoldView submit(EntitlementContext ctx, String entity, String store, ServiceUseSubmit c) {
        var current = orders.requireMerchant(ctx, entity, store, c.serviceOrderNo());
        if (current.command() == null && List.of("CREATED", "PREVIEWED").contains(current.status())) {
            return view(current.operationNo(), State.WAIT_AUTH);
        }
        return advance(orders.stage(ctx, entity, store, c));
    }
    @Override public HoldView query(EntitlementContext ctx, String entity, String operation) {
        var order = orders.findOperation(ctx.tenantNo(), entity, operation);
        if (order == null) return view(operation, State.NOT_FOUND);
        if (order.command() == null && List.of("CREATED", "PREVIEWED").contains(order.status())) {
            return view(operation, State.WAIT_AUTH);
        }
        return pay.query(order.context(), entity, operation);
    }
    @Override public HoldView recover(EntitlementContext ctx, String entity, String operation) {
        var order = orders.findOperation(ctx.tenantNo(), entity, operation);
        return order == null ? view(operation, State.NOT_FOUND) : advance(order);
    }
    @Override public HoldView cancel(EntitlementContext ctx, String entity, String store, String no, String reason) {
        return advance(orders.cancel(ctx, entity, store, no, reason));
    }
    private HoldView advance(ServiceOrderSnapshot order) {
        if (List.of("CREATED", "PREVIEWED", "AUTHORIZED").contains(order.status())) {
            return view(order.operationNo(), State.WAIT_AUTH);
        }
        try {
            if (List.of("CANCEL_DECIDED", "RELEASED").contains(order.status())) {
                var released = pay.release(order.context(), new CancelUse(order.operationNo(), order.fact().source(),
                        order.decision()));
                orders.acknowledge(order.context(), order.fact().entityNo(), released); return released;
            }
            if ("PENDING".equals(order.status())) {
                var held = pay.prepare(order.context(), order.command());
                if (held.status() != State.HELD && held.status() != State.COMMITTED) return processing(order);
                var fact = facts.query(order.context(), order.fact().entityNo(), order.operationNo());
                if (fact.operation().status() != State.HELD && fact.operation().status() != State.COMMITTED) {
                    return processing(order);
                }
                order = orders.accept(order.context(), order.fact().entityNo(), fact);
            }
            if ("COMMIT_DECIDED".equals(order.status())) {
                pay.commit(order.context(), new UseDecision(order.operationNo(), order.fact().source(),
                        order.decision()));
            }
            var result = pay.query(order.context(), order.fact().entityNo(), order.operationNo());
            if (result.status() != State.COMMITTED) return processing(order);
            orders.acknowledge(order.context(), order.fact().entityNo(), result); return result;
        } catch (BizException failure) {
            if ("PENDING".equals(order.status()) && definitive(failure)) {
                var stopped = orders.cancel(order.context(), order.fact().entityNo(), order.fact().storeNo(),
                        order.serviceOrderNo(), "PREPARE_REJECTED");
                return advance(stopped);
            }
            return processing(order);
        } catch (RuntimeException unknown) { return processing(order); }
    }
    private static boolean definitive(BizException failure) {
        return switch (failure.errorCode()) {
            case BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT -> true;
            default -> false;
        };
    }
    private static HoldView processing(ServiceOrderSnapshot order) { return view(order.operationNo(), State.PROCESSING);
    }
    private static HoldView view(String no, State state) { return new HoldView(no, state, null, null, null); }
}
