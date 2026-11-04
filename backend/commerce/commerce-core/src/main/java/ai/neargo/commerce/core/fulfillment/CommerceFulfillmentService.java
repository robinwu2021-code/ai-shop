package ai.neargo.commerce.core.fulfillment;

import static ai.neargo.commerce.core.mall.OrderState.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository.Operation;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.mall.MallSnapshot.*;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.core.subscription.SubscriptionService;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Trusted internal facts/worker boundary. Network calls occur only between committed local units.
 */
public final class CommerceFulfillmentService implements FulfillmentFacade, PaymentFacade {
    private final MallRepository orders;
    private final FulfillmentRepository repo;
    private final SubscriptionAccountRepository accounts;
    private final CommerceUnitOfWork uow;
    private final SubscriptionService subscriptions;
    private final PlatformPaymentPort payments;
    private final HardwareInventoryPort inventory;
    private final CommerceEventPersistencePort events;
    private final Clock clock;
    private final CommerceIdGenerator ids;
    private final String recipient;
    private final Set<String> routes;
    private final ActorAuthorizationPort authorization;

    public CommerceFulfillmentService(
            MallRepository orders,
            FulfillmentRepository repo,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            SubscriptionService subscriptions,
            PlatformPaymentPort payments,
            HardwareInventoryPort inventory,
            CommerceEventPersistencePort events,
            Clock clock,
            CommerceIdGenerator ids,
            String recipient,
            Set<String> routes,
            ActorAuthorizationPort authorization) {
        this.orders = orders;
        this.repo = repo;
        this.accounts = accounts;
        this.uow = uow;
        this.subscriptions = subscriptions;
        this.payments = Objects.requireNonNull(payments);
        this.inventory = Objects.requireNonNull(inventory);
        this.events = events;
        this.clock = clock;
        this.ids = ids;
        this.recipient = recipient;
        this.routes = Set.copyOf(routes);
        this.authorization = authorization;
        if (recipient == null || recipient.isBlank() || routes.isEmpty())
            throw new IllegalArgumentException("platform collection configuration required");
    }

    private static MallException fail(String code) {
        return new MallException(code);
    }

    private <T> T locked(String t, String no, Function<Order, T> fn) {
        if (t == null || t.isBlank() || no == null || no.isBlank()) throw fail("INVALID_REQUEST");
        return uow.required(
                () -> {
                    var location = repo.locate(t, no).orElseThrow(() -> fail("NOT_FOUND"));
                    var store = location.store();
                    var account = accounts.lockSubscriptionAccount(store);
                    if (account == null) throw fail("NOT_FOUND");
                    return fn.apply(loadAfterLocks(t, no, store, location.currency()));
                });
    }

    private Order loadAfterLocks(String t, String no, StoreRef store, String currency) {
        orders.lockBalance(store, currency);
        orders.lockSources(store, clock.millis());
        orders.lockLots(store, currency, clock.millis());
        return orders.order(t, no)
                .filter(o -> o.quote().store().equals(store))
                .orElseThrow(() -> fail("NOT_FOUND"));
    }

    private List<Operation> ops(Order o) {
        return repo.operations(o.quote().store().tenantNo(), o.orderNo());
    }

    private Optional<Operation> payment(Order o) {
        var all = ops(o).stream().filter(x -> x.kind().equals("PAYMENT")).toList();
        if (all.size() > 1) throw fail("AMBIGUOUS_PAYMENT");
        return all.stream().findFirst();
    }

    private Order transition(Order o, Payment p, Fulfillment f, Refund r) {
        var after = new Axes(p, f, r);
        return after.equals(o.axes()) ? o : orders.transition(o, after, null);
    }

    private static String id(String prefix, String... values) {
        return prefix + FactDigest.of(Arrays.asList(values)).substring(0, 48);
    }

    private static boolean succeeded(Operation op) {
        return op.inventoryResult() != null
                && op.inventoryResult().status() == AsyncStatus.SUCCEEDED;
    }

    private static boolean digitalDone(List<OrderComponent> components) {
        return components.stream()
                .filter(c -> c.snapshot().allocation().component().kind() != ComponentKind.HARDWARE)
                .allMatch(c -> c.fulfillmentStatus().equals("COMPLETED"));
    }

    private FulfillmentResult result(Order o) {
        var components = orders.components(o.quote().store(), o.orderNo());
        var all = ops(o);
        boolean hardwareDone =
                components.stream()
                        .filter(
                                c ->
                                        c.snapshot().allocation().component().kind()
                                                == ComponentKind.HARDWARE)
                        .allMatch(
                                c ->
                                        shipped(all, c.orderComponentNo())
                                                == c.snapshot().allocation().cycles().size());
        return new FulfillmentResult(
                o.orderNo(),
                digitalDone(components) ? AsyncStatus.SUCCEEDED : AsyncStatus.PENDING,
                hardwareDone ? AsyncStatus.SUCCEEDED : AsyncStatus.PENDING,
                accounts.readEntitlementVersion(o.quote().store()));
    }

    private long shipped(List<Operation> ops, String part) {
        return ops.stream()
                .filter(
                        x ->
                                x.kind().equals("SHIP")
                                        && x.componentNo().equals(part)
                                        && succeeded(x))
                .mapToLong(x -> x.inventory().quantity())
                .reduce(0, Math::addExact);
    }

    private void buyer(ActorContext a, StartPaymentCommand c, Order o) {
        if (a == null
                || a.subjectKind() != SubjectKind.ACCOUNT
                || !o.quote().store().equals(c.store())
                || !o.quote().store().tenantNo().equals(a.tenantNo())
                || !o.quote().buyerNo().equals(a.principalNo())
                || !authorization.authorize(a, c.store(), "commerce:mall:purchase").allowed())
            throw fail("FORBIDDEN");
    }

    public PaymentOutcome startPayment(ActorContext a, StartPaymentCommand c) {
        uow.requireOutsideTransaction();
        if (c == null
                || a == null
                || c.store() == null
                || !routes.contains(c.channel() + ":" + c.payMethod()))
            throw fail("INVALID_REQUEST");
        locked(
                a.tenantNo(),
                c.orderNo(),
                o -> {
                    buyer(a, c, o);
                    if (o.axes().payment() == Payment.CLOSED
                            || o.axes().payment() == Payment.CANCEL_PENDING
                            || clock.millis() >= o.quote().expiresAt()) throw fail("INVALID_STATE");
                    return null;
                });
        reserve(a.tenantNo(), c.orderNo());
        record Prepared(Operation op, boolean fresh) {}
        var prepared =
                locked(
                        a.tenantNo(),
                        c.orderNo(),
                        o -> {
                            buyer(a, c, o);
                            var preparedAt = clock.instant();
                            if (o.axes().payment() == Payment.CANCEL_PENDING
                                    || o.axes().payment() == Payment.CLOSED
                                    || !preparedAt.isBefore(
                                            Instant.ofEpochMilli(o.quote().expiresAt())))
                                throw fail("INVALID_STATE");
                            if (!reserved(o)) throw fail("INVENTORY_PENDING");
                            var prior = payment(o);
                            if (prior.isPresent()) {
                                var op = prior.get();
                                var p = op.payment();
                                if (!Objects.equals(c.channel(), p.channel())
                                        || !Objects.equals(c.payMethod(), p.payMethod())
                                        || !Objects.equals(c.payerReference(), p.payerReference()))
                                    throw fail("PAYMENT_REQUEST_CONFLICT");
                                return new Prepared(op, false);
                            }
                            String attempt = ids.nextBusinessNo("mall-payment");
                            var command =
                                    new PaymentIntentCommand(
                                            a.tenantNo(),
                                            o.orderNo(),
                                            recipient,
                                            o.view().payable(),
                                            Instant.ofEpochMilli(o.quote().expiresAt()),
                                            attempt,
                                            o.quote().buyerNo(),
                                            c.channel(),
                                            c.payMethod(),
                                            c.payerReference(),
                                            preparedAt);
                            var op =
                                    new Operation(
                                            attempt,
                                            o.orderNo(),
                                            null,
                                            "PAYMENT",
                                            command,
                                            null,
                                            null,
                                            null,
                                            null);
                            repo.save(a.tenantNo(), op);
                            transition(o, Payment.PAYING, Fulfillment.PENDING, Refund.NONE);
                            return new Prepared(op, true);
                        });
        var op = prepared.op();
        if (!prepared.fresh()
                && op.paymentResult() != null
                && (!op.paymentResult().checkoutParameters().isEmpty()
                        || op.paymentResult().status() == AsyncStatus.SUCCEEDED))
            return op.paymentResult();
        PaymentOutcome outcome;
        if (op.payment().amount().minor() == 0) outcome = zeroPayment(op);
        else
            try {
                outcome = prepared.fresh() ? payments.create(op.payment()) : queryOrReplay(op);
            } catch (RuntimeException e) {
                outcome = unknown(op);
            }
        return applyPayment(a.tenantNo(), op, outcome);
    }

    private Instant zeroPreparedAt(Operation op) {
        var time = op.payment().preparedAt();
        if (time == null || !time.isBefore(op.payment().expiresAt()))
            throw fail("ZERO_PAYMENT_TIME_UNPROVEN");
        return time;
    }

    private PaymentOutcome zeroPayment(Operation op) {
        return new PaymentOutcome(
                op.orderNo(),
                Optional.of(id("zero:", op.operationNo())),
                op.payment().platformMerchantNo(),
                op.payment().amount(),
                AsyncStatus.SUCCEEDED,
                Optional.of(zeroPreparedAt(op)),
                Optional.empty());
    }

    private PaymentOutcome closeZeroPayment(Operation op) {
        zeroPreparedAt(op);
        // This local fence is the persisted CANCEL_PENDING/CLOSED order state, not provider
        // evidence.
        return new PaymentOutcome(
                op.orderNo(),
                Optional.empty(),
                op.payment().platformMerchantNo(),
                op.payment().amount(),
                AsyncStatus.PENDING,
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                true,
                Optional.of(id("zero-close:", op.operationNo())));
    }

    private PaymentOutcome queryOrReplay(Operation op) {
        if (op.payment().amount().minor() == 0) return zeroPayment(op);
        var outcome = payments.query(query(op));
        validatePayment(op, outcome);
        if (outcome.status() == AsyncStatus.UNKNOWN
                && outcome.errorCode().filter("NOT_FOUND"::equals).isPresent()
                && !outcome.definitivelyClosed()) {
            boolean maySend =
                    locked(
                            op.payment().tenantNo(),
                            op.orderNo(),
                            o ->
                                    o.axes().payment() != Payment.CLOSED
                                            && o.axes().payment() != Payment.CANCEL_PENDING
                                            && o.axes().payment() != Payment.PAID
                                            && clock.millis() < o.quote().expiresAt());
            if (maySend)
                return payments.create(
                        op.payment()); // Same original idempotent intent, never a replacement.
        }
        return outcome;
    }

    private PaymentQuery query(Operation op) {
        return new PaymentQuery(op.payment().tenantNo(), op.orderNo(), op.operationNo());
    }

    private PaymentOutcome unknown(Operation op) {
        return new PaymentOutcome(
                op.orderNo(),
                Optional.empty(),
                op.payment().platformMerchantNo(),
                op.payment().amount(),
                AsyncStatus.UNKNOWN,
                Optional.empty(),
                Optional.of("UNRESOLVED"));
    }

    private void validatePayment(Operation op, PaymentOutcome r) {
        if (r == null
                || !op.orderNo().equals(r.orderNo())
                || !op.payment().platformMerchantNo().equals(r.platformMerchantNo())
                || !op.payment().amount().equals(r.amount())
                || r.status() == null
                || r.paymentNo() == null
                || r.paidAt() == null
                || r.closureReference() == null
                || r.definitivelyClosed()
                        && (r.closureReference().filter(s -> !s.isBlank()).isEmpty()
                                || r.status() == AsyncStatus.SUCCEEDED)
                || r.status() == AsyncStatus.SUCCEEDED
                        && (r.paymentNo().filter(s -> !s.isBlank()).isEmpty()
                                || r.paidAt().isEmpty())) throw fail("PAYMENT_FACT_MISMATCH");
        if (op.paymentResult() != null
                && op.paymentResult().paymentNo().isPresent()
                && r.paymentNo().isPresent()
                && !op.paymentResult().paymentNo().equals(r.paymentNo()))
            throw fail("PAYMENT_FACT_MISMATCH");
    }

    private PaymentOutcome applyPayment(String t, Operation requested, PaymentOutcome response) {
        return locked(
                t,
                requested.orderNo(),
                o -> {
                    var op = payment(o).orElseThrow(() -> fail("PAYMENT_INTENT_REQUIRED"));
                    validatePayment(op, response);
                    if (op.payment().amount().minor() == 0
                            && response.status() == AsyncStatus.SUCCEEDED
                            && (o.axes().payment() == Payment.CANCEL_PENDING
                                    || o.axes().payment() == Payment.CLOSED)) {
                        var closed = closeZeroPayment(op);
                        if (o.axes().payment() != Payment.CLOSED)
                            repo.save(t, op.paymentResult(closed));
                        return closed;
                    }
                    var r = response;
                    if (r.paymentNo().isEmpty()
                            && op.paymentResult() != null
                            && op.paymentResult().paymentNo().isPresent())
                        r =
                                new PaymentOutcome(
                                        r.orderNo(),
                                        op.paymentResult().paymentNo(),
                                        r.platformMerchantNo(),
                                        r.amount(),
                                        r.status(),
                                        r.paidAt(),
                                        r.errorCode(),
                                        r.checkoutParameters(),
                                        r.definitivelyClosed(),
                                        r.closureReference());
                    if (r.status() == AsyncStatus.SUCCEEDED) {
                        // Bind the provider number from this matched query/create before entering
                        // the fact boundary.
                        if (op.paymentResult() == null
                                || op.paymentResult().paymentNo().isEmpty()) {
                            var binding =
                                    new PaymentOutcome(
                                            r.orderNo(),
                                            r.paymentNo(),
                                            r.platformMerchantNo(),
                                            r.amount(),
                                            AsyncStatus.PENDING,
                                            Optional.empty(),
                                            Optional.empty());
                            op = op.paymentResult(binding);
                            repo.save(t, op);
                        }
                        applyPaid(
                                o,
                                op,
                                new VerifiedPaymentFact(
                                        t,
                                        id("query:", op.operationNo()),
                                        o.orderNo(),
                                        r.paymentNo().orElseThrow(),
                                        r.platformMerchantNo(),
                                        r.amount(),
                                        r.paidAt().orElseThrow(),
                                        (op.payment().amount().minor() == 0
                                                        ? "INTERNAL_ZERO_PAYMENT:"
                                                        : "PORT_QUERY:")
                                                + op.operationNo()));
                        return r;
                    }
                    if (o.axes().payment() == Payment.PAID
                            || o.axes().payment() == Payment.CLOSED
                            || op.paymentResult() != null
                                    && op.paymentResult().definitivelyClosed())
                        return op.paymentResult() == null ? r : op.paymentResult();
                    repo.save(t, op.paymentResult(r));
                    if (o.axes().payment() != Payment.CANCEL_PENDING)
                        transition(
                                o,
                                r.status() == AsyncStatus.UNKNOWN
                                        ? Payment.PAYMENT_UNKNOWN
                                        : Payment.PAYING,
                                Fulfillment.PENDING,
                                Refund.NONE);
                    return r;
                });
    }

    public FulfillmentResult confirmPayment(VerifiedPaymentFact f) {
        if (f == null
                || f.eventNo() == null
                || f.eventNo().isBlank()
                || f.verificationReference() == null
                || f.verificationReference().isBlank()
                || f.paidAt() == null
                || f.paidAt().isAfter(clock.instant().plusSeconds(300)))
            throw fail("INVALID_PAYMENT_FACT");
        return locked(
                f.tenantNo(),
                f.orderNo(),
                o -> {
                    var op = payment(o).orElseThrow(() -> fail("PAYMENT_INTENT_REQUIRED"));
                    applyPaid(o, op, f);
                    return result(orders.order(f.tenantNo(), f.orderNo()).orElseThrow());
                });
    }

    private void applyPaid(Order o, Operation op, VerifiedPaymentFact f) {
        if (op.paymentResult() == null
                || op.paymentResult().paymentNo().isEmpty()
                || !op.paymentResult().paymentNo().get().equals(f.paymentNo())
                || !op.payment().platformMerchantNo().equals(f.platformMerchantNo())
                || !op.payment().amount().equals(f.paid())) throw fail("PAYMENT_FACT_MISMATCH");
        String hash =
                FactDigest.of(
                        List.of(
                                f.orderNo(),
                                f.paymentNo(),
                                f.platformMerchantNo(),
                                f.paid().currency(),
                                Long.toString(f.paid().minor()),
                                f.paidAt().toString()));
        events.recordInbound(
                new InboundFact(
                        f.tenantNo(),
                        "MALL_PAYMENT",
                        f.eventNo(),
                        "PAID",
                        o.orderNo(),
                        hash,
                        clock.instant()));
        // A second event cannot alter already accepted payment economics/time.
        if (op.paymentResult().status() == AsyncStatus.SUCCEEDED
                && !op.paymentResult().paidAt().equals(Optional.of(f.paidAt())))
            throw fail("PAYMENT_FACT_MISMATCH");
        if (o.axes().payment() == Payment.PAID) return;
        boolean late =
                o.axes().payment() == Payment.CLOSED
                        || f.paidAt().toEpochMilli() > o.quote().expiresAt()
                        || op.paymentResult().definitivelyClosed();
        var paid =
                new PaymentOutcome(
                        o.orderNo(),
                        Optional.of(f.paymentNo()),
                        f.platformMerchantNo(),
                        f.paid(),
                        AsyncStatus.SUCCEEDED,
                        Optional.of(f.paidAt()),
                        Optional.empty());
        repo.save(f.tenantNo(), op.paymentResult(paid));
        repo.paid(o, f, op.operationNo());
        if (!late) repo.prepareDigital(o);
        transition(
                o,
                Payment.PAID,
                late ? Fulfillment.FAILED : Fulfillment.PENDING,
                late ? Refund.REFUNDING : Refund.NONE);
    }

    public FulfillmentResult fulfill(FulfillmentQuery q) {
        if (q == null) throw fail("INVALID_REQUEST");
        return locked(
                q.tenantNo(),
                q.orderNo(),
                o -> {
                    if (o.axes().payment() != Payment.PAID || o.axes().refund() != Refund.NONE)
                        throw fail("INVALID_STATE");
                    var components = orders.components(o.quote().store(), o.orderNo());
                    if (!digitalDone(components)) {
                        var batch = new ArrayList<SubscriptionService.FrozenDelivery>();
                        for (var part : components) {
                            var snapshot = part.snapshot();
                            var a = snapshot.allocation();
                            var c = a.component();
                            if (c.kind() == ComponentKind.HARDWARE) continue;
                            for (var cycle : a.cycles()) {
                                String ref = part.orderComponentNo() + ":" + cycle.sequence();
                                var d =
                                        new SubscriptionService.Delivery(
                                                ref,
                                                SubscriptionService.Origin.ORDER,
                                                c.planVersionNo(),
                                                c.entitlementCode(),
                                                Math.multiplyExact(
                                                        c.periodDays().longValue(), 86_400_000L),
                                                cycle.cash(),
                                                cycle.credit(),
                                                o.quote().currency(),
                                                c.gift());
                                batch.add(
                                        new SubscriptionService.FrozenDelivery(
                                                d, snapshot.plan(), snapshot.ends()));
                            }
                        }
                        subscriptions.deliverOrder(o.quote().store(), o.orderNo(), batch);
                        repo.digitalCompleted(o);
                        repo.settleHolds(o, true);
                    }
                    return refresh(o);
                });
    }

    private FulfillmentResult refresh(Order o) {
        var r = result(o);
        boolean all =
                r.digitalStatus() == AsyncStatus.SUCCEEDED
                        && r.hardwareStatus() == AsyncStatus.SUCCEEDED;
        var after =
                transition(
                        o,
                        Payment.PAID,
                        all ? Fulfillment.COMPLETED : Fulfillment.PARTIAL,
                        o.axes().refund());
        return result(after);
    }

    private boolean reserved(Order o) {
        var all = ops(o);
        return orders.components(o.quote().store(), o.orderNo()).stream()
                .filter(c -> c.snapshot().allocation().component().kind() == ComponentKind.HARDWARE)
                .allMatch(
                        c ->
                                all.stream()
                                        .anyMatch(
                                                x ->
                                                        x.kind().equals("RESERVE")
                                                                && x.componentNo()
                                                                        .equals(
                                                                                c
                                                                                        .orderComponentNo())
                                                                && succeeded(x)));
    }

    private record Dispatch(Operation operation, boolean fresh) {}

    private void reserve(String t, String no) {
        var prepared =
                locked(
                        t,
                        no,
                        o -> {
                            if (o.axes().payment() == Payment.CANCEL_PENDING
                                    || o.axes().payment() == Payment.CLOSED
                                    || o.axes().payment() == Payment.PAID)
                                return List.<Dispatch>of();
                            var all = ops(o);
                            var pending = new ArrayList<Dispatch>();
                            for (var c : orders.components(o.quote().store(), o.orderNo())) {
                                var a = c.snapshot().allocation();
                                if (a.component().kind() != ComponentKind.HARDWARE) continue;
                                var existing =
                                        all.stream()
                                                .filter(
                                                        x ->
                                                                x.kind().equals("RESERVE")
                                                                        && x.componentNo()
                                                                                .equals(
                                                                                        c
                                                                                                .orderComponentNo()))
                                                .findFirst();
                                if (existing.isPresent()) {
                                    if (!succeeded(existing.get()))
                                        pending.add(new Dispatch(existing.get(), false));
                                    continue;
                                }
                                String ref = id("reserve:", no, c.orderComponentNo());
                                var cmd =
                                        new InventoryCommand(
                                                t,
                                                no,
                                                c.orderComponentNo(),
                                                a.component().hardwareSkuNo(),
                                                a.cycles().size(),
                                                ref);
                                var op =
                                        new Operation(
                                                ref,
                                                no,
                                                c.orderComponentNo(),
                                                "RESERVE",
                                                null,
                                                cmd,
                                                null,
                                                null,
                                                null);
                                repo.save(t, op);
                                pending.add(new Dispatch(op, true));
                            }
                            return pending;
                        });
        for (var dispatch : prepared) {
            var op = dispatch.operation();
            InventoryOutcome response;
            try {
                if (dispatch.fresh()) response = inventory.reserve(op.inventory());
                else {
                    response =
                            inventory.query(
                                    new InventoryQuery(
                                            t, op.componentNo(), op.inventory().operationNo()));
                    validateInventory(op, response);
                    if (response.status() == AsyncStatus.UNKNOWN
                            && response.errorCode().filter("NOT_FOUND"::equals).isPresent())
                        response = inventory.reserve(op.inventory());
                }
            } catch (MallException e) {
                throw e;
            } catch (RuntimeException e) {
                continue;
            }
            applyInventory(t, op, response);
        }
        locked(
                t,
                no,
                o -> {
                    if (o.axes().payment() == Payment.UNPAID && reserved(o))
                        transition(o, Payment.UNPAID, Fulfillment.PENDING, Refund.NONE);
                    return null;
                });
    }

    private void validateInventory(Operation op, InventoryOutcome r) {
        if (r == null
                || r.status() == null
                || !op.componentNo().equals(r.orderComponentNo())
                || !op.inventory().operationNo().equals(r.operationNo())
                || r.reservedQuantity() < 0
                || r.shippedQuantity() < 0
                || r.status() == AsyncStatus.SUCCEEDED
                        && (op.kind().equals("RELEASE")
                                ? (r.reservedQuantity() != 0
                                        || r.shippedQuantity() != 0
                                        || r.operationQuantity() != 0)
                                : r.operationQuantity() != op.inventory().quantity()))
            throw fail("INVENTORY_FACT_MISMATCH");
    }

    private void applyInventory(String t, Operation requested, InventoryOutcome r) {
        locked(
                t,
                requested.orderNo(),
                o -> {
                    var op =
                            ops(o).stream()
                                    .filter(x -> x.operationNo().equals(requested.operationNo()))
                                    .findFirst()
                                    .orElseThrow();
                    validateInventory(op, r);
                    if (succeeded(op)) return null;
                    repo.save(t, op.inventoryResult(r));
                    if (op.kind().equals("SHIP") && r.status() == AsyncStatus.SUCCEEDED) {
                        repo.shipment(o, op);
                        refresh(o);
                    }
                    return null;
                });
    }

    public FulfillmentResult confirmShipment(HardwareShipmentCommand c) {
        uow.requireOutsideTransaction();
        if (c == null
                || c.operationNo() == null
                || c.operationNo().isBlank()
                || c.operationNo().length() > 64
                || c.quantity() <= 0
                || c.quantity() > Integer.MAX_VALUE
                || c.carrierCode() == null
                || c.carrierCode().isBlank()
                || c.trackingNo() == null
                || c.trackingNo().isBlank()) throw fail("INVALID_REQUEST");
        var op =
                locked(
                        c.tenantNo(),
                        c.orderNo(),
                        o -> {
                            if (o.axes().payment() != Payment.PAID
                                    || o.axes().refund() != Refund.NONE)
                                throw fail("INVALID_STATE");
                            var component =
                                    orders.components(o.quote().store(), o.orderNo()).stream()
                                            .filter(
                                                    x ->
                                                            x.orderComponentNo()
                                                                            .equals(
                                                                                    c
                                                                                            .orderComponentNo())
                                                                    && x.snapshot()
                                                                                    .allocation()
                                                                                    .component()
                                                                                    .kind()
                                                                            == ComponentKind
                                                                                    .HARDWARE)
                                            .findFirst()
                                            .orElseThrow(() -> fail("NOT_FOUND"));
                            var all = ops(o);
                            String ref =
                                    id("ship:", c.orderNo(), c.orderComponentNo(), c.operationNo());
                            var existing =
                                    all.stream()
                                            .filter(x -> x.operationNo().equals(ref))
                                            .findFirst();
                            if (existing.isPresent()) {
                                if (!c.equals(existing.get().shipment()))
                                    throw fail("OPERATION_CONFLICT");
                                return new Dispatch(existing.get(), false);
                            }
                            if (!reserved(o)) throw fail("INVENTORY_PENDING");
                            if (all.stream()
                                    .anyMatch(
                                            x ->
                                                    x.kind().equals("SHIP")
                                                            && x.componentNo()
                                                                    .equals(c.orderComponentNo())
                                                            && !succeeded(x)))
                                throw fail("SHIPMENT_UNKNOWN");
                            if (c.quantity()
                                    > component.snapshot().allocation().cycles().size()
                                            - shipped(all, c.orderComponentNo()))
                                throw fail("SHIPMENT_EXCEEDS_REMAINING");
                            var cmd =
                                    new InventoryCommand(
                                            c.tenantNo(),
                                            c.orderNo(),
                                            c.orderComponentNo(),
                                            component
                                                    .snapshot()
                                                    .allocation()
                                                    .component()
                                                    .hardwareSkuNo(),
                                            c.quantity(),
                                            ref,
                                            c.carrierCode(),
                                            c.trackingNo());
                            var next =
                                    new Operation(
                                            ref,
                                            c.orderNo(),
                                            c.orderComponentNo(),
                                            "SHIP",
                                            null,
                                            cmd,
                                            c,
                                            null,
                                            null);
                            repo.save(c.tenantNo(), next);
                            return new Dispatch(next, true);
                        });
        var shipment = op.operation();
        if (!succeeded(shipment)) {
            try {
                applyInventory(c.tenantNo(), shipment, shipmentOutcome(shipment, op.fresh()));
            } catch (MallException e) {
                throw e;
            } catch (RuntimeException ignored) {
            }
        }
        return locked(c.tenantNo(), c.orderNo(), this::result);
    }

    private InventoryOutcome shipmentOutcome(Operation shipment, boolean fresh) {
        if (fresh) return inventory.ship(shipment.inventory());
        var response =
                inventory.query(
                        new InventoryQuery(
                                shipment.inventory().tenantNo(),
                                shipment.componentNo(),
                                shipment.inventory().operationNo()));
        validateInventory(shipment, response);
        if (response.status() == AsyncStatus.UNKNOWN
                && response.errorCode().filter("NOT_FOUND"::equals).isPresent())
            return inventory.ship(shipment.inventory());
        return response;
    }

    public FulfillmentResult recover(FulfillmentQuery q) {
        uow.requireOutsideTransaction();
        if (q == null) throw fail("INVALID_REQUEST");
        var state =
                locked(
                        q.tenantNo(),
                        q.orderNo(),
                        o -> {
                            // Decide local zero settlement and expiry under this same account lock.
                            // Explicit preexisting cancellation/closure wins before settlement.
                            if (o.axes().payment() != Payment.PAID
                                    && o.axes().payment() != Payment.CANCEL_PENDING
                                    && o.axes().payment() != Payment.CLOSED) {
                                var zero =
                                        payment(o).filter(op -> op.payment().amount().minor() == 0);
                                if (zero.isPresent()) {
                                    applyPayment(q.tenantNo(), zero.get(), zeroPayment(zero.get()));
                                    return orders.order(q.tenantNo(), q.orderNo()).orElseThrow();
                                }
                            }

                            if (clock.millis() >= o.quote().expiresAt()
                                    && o.axes().payment() != Payment.PAID
                                    && o.axes().payment() != Payment.CLOSED)
                                o =
                                        transition(
                                                o,
                                                Payment.CANCEL_PENDING,
                                                o.axes().fulfillment(),
                                                Refund.NONE);
                            return o;
                        });
        if (state.axes().payment() == Payment.CANCEL_PENDING) return cancel(q);
        if (state.axes().payment() == Payment.CLOSED)
            return locked(q.tenantNo(), q.orderNo(), this::result);
        if (state.axes().payment() != Payment.PAID) {
            reserve(q.tenantNo(), q.orderNo());
            var op = locked(q.tenantNo(), q.orderNo(), this::payment);
            if (op.isPresent())
                try {
                    applyPayment(q.tenantNo(), op.get(), queryOrReplay(op.get()));
                } catch (MallException e) {
                    throw e;
                } catch (RuntimeException ignored) {
                }
        }
        var pending =
                locked(
                        q.tenantNo(),
                        q.orderNo(),
                        o ->
                                ops(o).stream()
                                        .filter(x -> x.kind().equals("SHIP") && !succeeded(x))
                                        .toList());
        for (var op : pending)
            try {
                applyInventory(q.tenantNo(), op, shipmentOutcome(op, false));
            } catch (MallException e) {
                throw e;
            } catch (RuntimeException ignored) {
            }
        var current = locked(q.tenantNo(), q.orderNo(), Function.identity());
        if (current.axes().payment() == Payment.PAID && current.axes().refund() == Refund.NONE)
            return fulfill(q);
        return locked(q.tenantNo(), q.orderNo(), this::result);
    }

    private FulfillmentResult cancel(FulfillmentQuery q) {
        var attempt = locked(q.tenantNo(), q.orderNo(), this::payment);
        if (attempt.isPresent()) {
            PaymentOutcome response;
            try {
                response =
                        attempt.get().payment().amount().minor() == 0
                                ? closeZeroPayment(attempt.get())
                                : payments.close(query(attempt.get()));
            } catch (MallException e) {
                throw e;
            } catch (RuntimeException e) {
                return locked(q.tenantNo(), q.orderNo(), this::result);
            }
            applyPayment(q.tenantNo(), attempt.get(), response);
            if (response.status() == AsyncStatus.SUCCEEDED) {
                var paidOrder = locked(q.tenantNo(), q.orderNo(), Function.identity());
                return paidOrder.axes().refund() == Refund.NONE
                        ? fulfill(q)
                        : locked(q.tenantNo(), q.orderNo(), this::result);
            }
            if (!response.definitivelyClosed())
                return locked(q.tenantNo(), q.orderNo(), this::result);
        }
        var releases =
                locked(
                        q.tenantNo(),
                        q.orderNo(),
                        o -> {
                            if (o.axes().payment() != Payment.CANCEL_PENDING)
                                return List.<Operation>of();
                            var all = ops(o);
                            var pending = new ArrayList<Operation>();
                            for (var reserve : all)
                                if (reserve.kind().equals("RESERVE")) {
                                    String ref = id("release:", reserve.componentNo());
                                    var release =
                                            all.stream()
                                                    .filter(x -> x.operationNo().equals(ref))
                                                    .findFirst()
                                                    .orElseGet(
                                                            () -> {
                                                                var op =
                                                                        new Operation(
                                                                                ref,
                                                                                o.orderNo(),
                                                                                reserve
                                                                                        .componentNo(),
                                                                                "RELEASE",
                                                                                null,
                                                                                reserve.inventory(),
                                                                                null,
                                                                                null,
                                                                                null);
                                                                repo.save(q.tenantNo(), op);
                                                                return op;
                                                            });
                                    if (!succeeded(release)) pending.add(release);
                                }
                            return pending;
                        });
        for (var op : releases)
            try {
                applyInventory(
                        q.tenantNo(),
                        op,
                        inventory.release(
                                new InventoryQuery(
                                        q.tenantNo(),
                                        op.componentNo(),
                                        op.inventory().operationNo())));
            } catch (MallException e) {
                throw e;
            } catch (RuntimeException ignored) {
            }
        return locked(
                q.tenantNo(),
                q.orderNo(),
                o -> {
                    if (o.axes().payment() != Payment.CANCEL_PENDING) return result(o);
                    var all = ops(o);
                    boolean closed =
                            payment(o).isEmpty()
                                    || payment(o).get().paymentResult() != null
                                            && payment(o)
                                                    .get()
                                                    .paymentResult()
                                                    .definitivelyClosed();
                    boolean released =
                            all.stream()
                                    .filter(x -> x.kind().equals("RESERVE"))
                                    .allMatch(
                                            x ->
                                                    all.stream()
                                                            .anyMatch(
                                                                    y ->
                                                                            y.kind()
                                                                                            .equals(
                                                                                                    "RELEASE")
                                                                                    && y.componentNo()
                                                                                            .equals(
                                                                                                    x
                                                                                                            .componentNo())
                                                                                    && succeeded(
                                                                                            y)));
                    if (closed && released) {
                        repo.settleHolds(o, false);
                        o = transition(o, Payment.CLOSED, o.axes().fulfillment(), Refund.NONE);
                    }
                    return result(o);
                });
    }
}
