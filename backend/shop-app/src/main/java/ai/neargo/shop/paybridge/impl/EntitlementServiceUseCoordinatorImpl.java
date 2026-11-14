package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
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
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private ObjectProvider<ServiceWorkOrderUsePort> workOrders;
    private ObjectProvider<ServiceWorkOrderOriginPort> origins;
    private ObjectProvider<StoredValueUseQuotePort> moneyQuotes;

    @Autowired
    public void setWorkOrderOwners(ObjectProvider<ServiceWorkOrderUsePort> workOrders,
            ObjectProvider<ServiceWorkOrderOriginPort> origins, ObjectProvider<StoredValueUseQuotePort> moneyQuotes) {
        this.workOrders = workOrders; this.origins = origins; this.moneyQuotes = moneyQuotes;
    }
    public EntitlementServiceUseCoordinatorImpl(EntitlementServiceOrderService orders, EntitlementQueryPort queries,
                                               EntitlementUsePort pay, EntitlementUseFactsPort facts) {
        this.orders = orders; this.queries = queries; this.pay = pay; this.facts = facts;
    }
    @Override public PreviewView preview(EntitlementContext ctx, String entity, String store, String no,
                                         String instance,
                                         LocalDate requestedDay) {
        var order = orders.requireMerchant(ctx, entity, store, no);
        validateWorkOrderPreview(ctx, entity, store, order);
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
    @Override public StoredValueUseQuotePort.Quote previewWorkOrderMoney(EntitlementContext ctx, String entity,
            String store, String no, ai.neargo.shop.spi.storedvalue.StoredValueWallets.Choice choice) {
        var order = orders.requireMerchant(ctx, entity, store, no);
        if (order.workOrderOrigin() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        validateWorkOrderPreview(ctx, entity, store, order);
        var quote = choice == null && order.preview() != null && order.preview().moneyQuote() != null
                && Instant.now().isBefore(order.preview().expiresAt()) ? order.preview().moneyQuote()
                : choice == null ? moneyQuotes.getObject().quote(ctx, order.fact().source())
                        : moneyQuotes.getObject().quote(ctx, order.fact().source(), choice);
        if (quote.redeemable() && (order.preview() == null || order.preview().moneyQuote() == null
                || !quote.quoteRef().equals(order.preview().ref()))) {
            orders.bindMoneyPreview(ctx, entity, store, no, quote);
        }
        return quote;
    }

    private void validateWorkOrderPreview(EntitlementContext ctx, String entity, String store,
            ServiceOrderSnapshot order) {
        if (order.workOrderOrigin() == null) return;
        workOrderOwner().inspect(lookup(order));
        if (order.workOrderClaim() == null) {
            var expected = order.workOrderOrigin();
            var current = origins.getObject().resolve(entity, store, ctx.actorNo(),
                    expected.workOrderNo(), expected.taskNo());
            if (!expected.equals(current)) throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private ServiceWorkOrderUsePort workOrderOwner() {
        var owner = workOrders == null ? null : workOrders.getIfAvailable();
        if (owner == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return owner;
    }

    private static ServiceWorkOrderUsePort.Lookup lookup(ServiceOrderSnapshot order) {
        return new ServiceWorkOrderUsePort.Lookup(order.context().tenantNo(), order.fact().entityNo(),
                order.fact().storeNo(), order.operationNo());
    }

    private boolean workOrderState(ServiceOrderSnapshot order, ServiceWorkOrderUsePort.Binding binding,
            ServiceWorkOrderUsePort.State expected, String usage) {
        return binding != null && lookup(order).equals(binding.lookup()) && binding.state() == expected
                && order.workOrderOrigin().equals(binding.origin())
                && order.fact().orderSnapshotHash().equals(binding.sourceHash())
                && (usage == null || usage.equals(binding.usageNo()));
    }
    @Override public HoldView submit(EntitlementContext ctx, String entity, String store, ServiceUseSubmit c) {
        var current = orders.requireMerchant(ctx, entity, store, c.serviceOrderNo());
        if (current.command() == null && List.of("CREATED", "PREVIEWED").contains(current.status())) {
            return view(current.operationNo(), State.WAIT_AUTH);
        }
        if (current.workOrderOrigin() != null && current.command() == null) {
            var claim = workOrderOwner().reserve(ctx, lookup(current));
            orders.bindWorkOrderClaim(ctx, entity, store, current.serviceOrderNo(), claim);
        }
        return advance(orders.stage(ctx, entity, store, c));
    }
    @Override public HoldView query(EntitlementContext ctx, String entity, String operation) {
        var order = orders.findOperation(ctx.tenantNo(), entity, operation);
        if (order == null) return view(operation, State.NOT_FOUND);
        if (order.command() == null && List.of("CREATED", "PREVIEWED").contains(order.status())) {
            return view(operation, State.WAIT_AUTH);
        }
        var observation = originalObservation(order);
        if (order.workOrderOrigin() != null
                && (observation.status() == State.COMMITTED || observation.status() == State.RELEASED)) {
            try {
                var claim = workOrderOwner().inspect(lookup(order));
                boolean unreservedRelease = observation.status() == State.RELEASED && "RELEASED".equals(order.status())
                        && claim != null && lookup(order).equals(claim.lookup())
                        && claim.state() == ServiceWorkOrderUsePort.State.NOT_FOUND;
                var expected = observation.status() == State.COMMITTED
                        ? ServiceWorkOrderUsePort.State.BOUND : ServiceWorkOrderUsePort.State.RELEASED;
                if (!unreservedRelease && !workOrderState(order, claim, expected, observation.usageNo())) {
                    return processing(order);
                }
            } catch (RuntimeException unknown) {
                return processing(order);
            }
        }
        return observation;
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
                if (!order.operationNo().equals(released.operationNo())) return processing(order);
                orders.acknowledge(order.context(), order.fact().entityNo(), released);
                if (order.workOrderOrigin() != null
                        && !workOrderState(order, workOrderOwner().reconcile(lookup(order)),
                                ServiceWorkOrderUsePort.State.RELEASED, null)) return processing(order);
                return released;
            }
            if (order.workOrderOrigin() != null) {
                var claim = workOrderOwner().inspect(lookup(order));
                if (!workOrderState(order, claim, ServiceWorkOrderUsePort.State.RESERVED, null)
                        && !workOrderState(order, claim, ServiceWorkOrderUsePort.State.BOUND, null)) {
                    return processing(order);
                }
            }
            if ("PENDING".equals(order.status())) {
                var held = pay.prepare(order.context(), order.command());
                if (!order.operationNo().equals(held.operationNo())
                        || (held.status() != State.HELD && held.status() != State.COMMITTED)) return processing(order);
                var fact = facts.query(order.context(), order.fact().entityNo(), order.operationNo());
                if (!matchesOriginal(order, fact)
                        || (fact.operation().status() != State.HELD && fact.operation().status() != State.COMMITTED)) {
                    return processing(order);
                }
                order = orders.accept(order.context(), order.fact().entityNo(), fact);
            }
            if ("COMMIT_DECIDED".equals(order.status())) {
                pay.commit(order.context(), new UseDecision(order.operationNo(), order.fact().source(),
                        order.decision()));
            }
            var result = originalObservation(order);
            if (result.status() != State.COMMITTED) return processing(order);
            orders.acknowledge(order.context(), order.fact().entityNo(), result);
            if (order.workOrderOrigin() != null
                    && !workOrderState(order, workOrderOwner().reconcile(lookup(order)),
                            ServiceWorkOrderUsePort.State.BOUND, result.usageNo())) return processing(order);
            return result;
        } catch (BizException failure) {
            if ("PENDING".equals(order.status()) && definitive(failure)) {
                var stopped = orders.cancel(order.context(), order.fact().entityNo(), order.fact().storeNo(),
                        order.serviceOrderNo(), "PREPARE_REJECTED");
                return advance(stopped);
            }
            return processing(order);
        } catch (RuntimeException unknown) { return processing(order); }
    }
    private HoldView originalObservation(ServiceOrderSnapshot order) {
        var fact = facts.query(order.context(), order.fact().entityNo(), order.operationNo());
        return matchesOriginal(order, fact) ? fact.operation() : processing(order);
    }

    private static boolean matchesOriginal(ServiceOrderSnapshot order, UseFact fact) {
        if (!order.operationNo().equals(fact.operation().operationNo())) return false;
        if (fact.operation().status() != State.HELD && fact.operation().status() != State.COMMITTED) return true;
        return order.command() != null && order.command().equals(fact.command())
                && order.fact().orderSnapshotHash().equals(fact.orderSnapshotHash());
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
