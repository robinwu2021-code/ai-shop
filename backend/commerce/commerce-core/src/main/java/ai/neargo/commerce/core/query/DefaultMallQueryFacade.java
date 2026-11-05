package ai.neargo.commerce.core.query;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.offline.OfflineRepository;
import ai.neargo.commerce.core.spi.*;

import java.util.*;

/** Store-owned current reads; never queries a provider or changes coordination state. */
public final class DefaultMallQueryFacade implements MallQueryFacade {
    private final MallRepository orders;
    private final FulfillmentRepository operations;
    private final RefundRepository refunds;
    private final SubscriptionAccountRepository accounts;
    private final CommerceUnitOfWork uow;
    private final StoreDirectoryPort stores;
    private final ActorAuthorizationPort authorization;
    private final OfflineRepository offline;

    public DefaultMallQueryFacade(
            MallRepository orders,
            FulfillmentRepository operations,
            RefundRepository refunds,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort authorization) {
        this(orders, operations, refunds, accounts, uow, stores, authorization, null);
    }

    /** @param offline optional; when present, summaries carry the latest visible claim status */
    public DefaultMallQueryFacade(
            MallRepository orders,
            FulfillmentRepository operations,
            RefundRepository refunds,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort authorization,
            OfflineRepository offline) {
        this.orders = orders;
        this.operations = operations;
        this.refunds = refunds;
        this.accounts = accounts;
        this.uow = uow;
        this.stores = stores;
        this.authorization = authorization;
        this.offline = offline;
    }

    private void authorize(ActorContext actor, StoreRef store) {
        if (actor == null
                || store == null
                || !Objects.equals(actor.tenantNo(), store.tenantNo())
                || actor.principalNo() == null
                || actor.principalNo().isBlank()
                || actor.accountSessionFingerprint() == null
                || actor.accountSessionFingerprint().isBlank()
                || actor.subjectKind() != SubjectKind.ACCOUNT
                        && actor.subjectKind() != SubjectKind.OPERATOR)
            throw new MallException("FORBIDDEN");
        var permission =
                authorization.authorize(
                        actor,
                        store,
                        actor.subjectKind() == SubjectKind.OPERATOR
                                ? "commerce:mall:order:read"
                                : "commerce:mall:read");
        if (permission == null || !permission.allowed()) throw new MallException("FORBIDDEN");
        var facts =
                stores.find(store)
                        .filter(s -> store.equals(s.store()))
                        .orElseThrow(() -> new MallException("NOT_FOUND"));
        if (facts.model() != CommerceModel.STORE_V1
                || actor.subjectKind() == SubjectKind.ACCOUNT
                        && facts.status() != StoreStatus.ACTIVE)
            throw new MallException("STORE_UNAVAILABLE");
        var account = accounts.lockSubscriptionAccount(store);
        if (!store.equals(account.store())
                || !facts.merchantNo().equals(account.entityNo())
                || !facts.businessTypeCode().equals(account.businessTypeCode()))
            throw new MallException("VERSION_CONFLICT");
    }

    private MallOrderDetailView.Summary summary(ActorContext actor, MallSnapshot.Order order) {
        var lines = lines(order);
        return new MallOrderDetailView.Summary(
                order.view(),
                order.axes().payment().name(),
                order.axes().fulfillment().name(),
                order.axes().refund().name(),
                lines.isEmpty() ? null : lines.getFirst().title(),
                lines.size(),
                latestClaimStatus(actor, order));
    }

    private static List<MallOrderDetailView.Line> lines(MallSnapshot.Order order) {
        var lines = order.quote().lines();
        if (lines == null) return List.of();
        return lines.stream()
                .map(
                        l ->
                                new MallOrderDetailView.Line(
                                        l.sku().productNo(),
                                        l.sku().skuNo(),
                                        l.sku().skuVersionNo(),
                                        l.sku().title(),
                                        l.quantity()))
                .toList();
    }

    /** Same visibility as the claims endpoint: merchants see their own declarations only. */
    private String latestClaimStatus(ActorContext actor, MallSnapshot.Order order) {
        if (offline == null
                || order.quote().purchase() == null
                || order.quote().purchase().offline() == null) return null;
        return offline.claims(order.quote().store().tenantNo(), order.orderNo()).stream()
                .filter(
                        c ->
                                actor.subjectKind() == SubjectKind.OPERATOR
                                        || actor.principalNo().equals(c.submitterNo()))
                .max(
                        Comparator.comparingLong(OfflineRepository.Claim::submittedAt)
                                .thenComparing(c -> c.command().claimNo()))
                .map(OfflineRepository.Claim::status)
                .orElse(null);
    }

    public List<MallOrderDetailView.Summary> orders(
            ActorContext actor, StoreRef store, String after, int limit) {
        if (limit < 1 || limit > 100 || after != null && !after.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw new MallException("INVALID_REQUEST");
        return uow.required(
                () -> {
                    authorize(actor, store);
                    return orders.orders(store, after, limit).stream()
                            .map(o -> summary(actor, o))
                            .toList();
                });
    }

    public MallOrderDetailView order(ActorContext actor, OrderQuery query) {
        if (query == null
                || query.orderNo() == null
                || !query.orderNo().matches("[A-Za-z0-9_.:-]{1,64}"))
            throw new MallException("INVALID_REQUEST");
        return uow.required(
                () -> {
                    authorize(actor, query.store());
                    var order =
                            orders.order(actor.tenantNo(), query.orderNo())
                                    .filter(o -> query.store().equals(o.quote().store()))
                                    .orElseThrow(() -> new MallException("NOT_FOUND"));
                    var payments = new ArrayList<MallOrderDetailView.PaymentAttempt>();
                    var shipments = new ArrayList<MallOrderDetailView.Shipment>();
                    for (var operation : operations.operations(actor.tenantNo(), order.orderNo())) {
                        if ("PAYMENT".equals(operation.kind())) {
                            var p = operation.payment();
                            var result = operation.paymentResult();
                            payments.add(
                                    new MallOrderDetailView.PaymentAttempt(
                                            operation.operationNo(),
                                            p.channel(),
                                            p.payMethod(),
                                            p.amount(),
                                            result == null ? AsyncStatus.PENDING : result.status(),
                                            result == null ? null : result.paymentNo().orElse(null),
                                            result == null ? null : result.paidAt().orElse(null),
                                            result == null ? null : result.errorCode().orElse(null),
                                            result == null || result.paidAt().isEmpty()
                                                    ? null
                                                    : result.paidAtPrecisionMs()));
                        } else if ("SHIP".equals(operation.kind())) {
                            var s = operation.shipment();
                            var result = operation.inventoryResult();
                            shipments.add(
                                    new MallOrderDetailView.Shipment(
                                            operation.operationNo(),
                                            operation.componentNo(),
                                            s.carrierCode(),
                                            s.trackingNo(),
                                            s.quantity(),
                                            result == null ? AsyncStatus.PENDING : result.status(),
                                            result == null
                                                    ? null
                                                    : result.errorCode().orElse(null)));
                        }
                    }
                    return new MallOrderDetailView(
                            summary(actor, order),
                            order.quote().view(),
                            order.shippingAddress(),
                            List.copyOf(payments),
                            List.copyOf(shipments),
                            refunds.forOrder(order).stream()
                                    .map(RefundSnapshot.State::view)
                                    .toList(),
                            lines(order));
                });
    }
}
