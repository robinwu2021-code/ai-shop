package ai.neargo.commerce.core.fulfillment;

import static ai.neargo.commerce.core.mall.RefundSnapshot.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.FulfillmentQuery;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.*;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Durable request/review/freeze/dispatch/fact protocol. External cash is never inferred from
 * intent.
 */
public final class CommerceRefundService implements RefundFacade {
    private final MallRepository mall;
    private final RefundRepository refunds;
    private final FulfillmentRepository fulfillment;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionAccountRepository accounts;
    private final EndScheduleService schedule;
    private final CommerceUnitOfWork uow;
    private final ActorAuthorizationPort authorization;
    private final PlatformPaymentPort payment;
    private final Clock clock;

    public CommerceRefundService(
            MallRepository mall,
            RefundRepository refunds,
            FulfillmentRepository fulfillment,
            SubscriptionRepository subscriptions,
            SubscriptionAccountRepository accounts,
            EndScheduleService schedule,
            CommerceUnitOfWork uow,
            ActorAuthorizationPort authorization,
            PlatformPaymentPort payment,
            Clock clock) {
        this.mall = mall;
        this.refunds = refunds;
        this.fulfillment = fulfillment;
        this.subscriptions = subscriptions;
        this.accounts = accounts;
        this.schedule = schedule;
        this.uow = uow;
        this.authorization = authorization;
        this.payment = Objects.requireNonNull(payment);
        this.clock = clock;
    }

    private static MallException fail(String code) {
        return new MallException(code);
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank();
    }

    private <T> T locked(String tenant, String orderNo, Function<MallSnapshot.Order, T> action) {
        if (!text(tenant) || !text(orderNo)) throw fail("INVALID_REQUEST");
        return uow.required(
                () -> {
                    var location =
                            fulfillment
                                    .locate(tenant, orderNo)
                                    .orElseThrow(() -> fail("NOT_FOUND"));
                    accounts.lockSubscriptionAccount(location.store());
                    mall.lockBalance(location.store(), location.currency());
                    mall.lockSources(location.store(), clock.millis());
                    mall.lockLots(location.store(), location.currency(), clock.millis());
                    var order = mall.order(tenant, orderNo).orElseThrow(() -> fail("NOT_FOUND"));
                    return action.apply(order);
                });
    }

    private void authorize(ActorContext actor, MallSnapshot.Order order, boolean ops) {
        var store = order.quote().store();
        if (actor == null
                || !store.tenantNo().equals(actor.tenantNo())
                || !text(actor.principalNo())
                || !text(actor.accountSessionFingerprint())
                || actor.subjectKind() != (ops ? SubjectKind.OPERATOR : SubjectKind.ACCOUNT)
                || ops && actor.principalNo().equals(order.quote().buyerNo()))
            throw fail("FORBIDDEN");
        var decision =
                authorization.authorize(
                        actor,
                        store,
                        ops ? "commerce:mall:refund:approve" : "commerce:mall:refund:request");
        if (decision == null || !decision.allowed()) throw fail("FORBIDDEN");
    }

    private void coordinates(StoreRef store, MallSnapshot.Order order) {
        if (!order.quote().store().equals(store)) throw fail("NOT_FOUND");
    }

    private State state(MallSnapshot.Order order, String no) {
        return refunds.find(order.quote().store().tenantNo(), no)
                .filter(
                        s ->
                                s.preview().orderNo().equals(order.orderNo())
                                        && s.preview().store().equals(order.quote().store()))
                .orElseThrow(() -> fail("NOT_FOUND"));
    }

    private static boolean buyerRefundNo(String no) {
        return no != null
                && no.matches("[A-Za-z0-9][A-Za-z0-9:._-]{0,63}")
                && !no.toLowerCase(Locale.ROOT).startsWith("late-");
    }

    public RefundPreviewView preview(ActorContext actor, RefundPreviewCommand c) {
        if (c == null || !buyerRefundNo(c.refundNo())) throw fail("INVALID_REQUEST");
        return locked(
                actor == null ? null : actor.tenantNo(),
                c.orderNo(),
                o -> {
                    authorize(actor, o, false);
                    coordinates(c.store(), o);
                    return previewLocked(actor, o, c).preview().view();
                });
    }

    private State previewLocked(ActorContext actor, MallSnapshot.Order o, RefundPreviewCommand c) {
        var existing = refunds.find(actor.tenantNo(), c.refundNo());
        if (existing.isPresent()) {
            var saved = existing.get();
            var p = refunds.requestPreview(actor.tenantNo(), c.refundNo());
            if (!p.orderNo().equals(o.orderNo())
                    || !p.buyerNo().equals(actor.principalNo())
                    || !c.sourcePeriodNos().isEmpty()
                            && !new HashSet<>(c.sourcePeriodNos())
                                    .equals(new HashSet<>(p.view().sourcePeriodNos())))
                throw fail("IDEMPOTENCY_CONFLICT");
            return new State(p, saved.status(), saved.approvedCash(), saved.restoredCredit(),
                    saved.reviewer(), saved.reviewEvidence(), saved.command(), saved.fact(),
                    saved.reviewedAt(), saved.requestDigest(), saved.reviewQuoteNo());
        }
        var calculated = calculatePreview(actor.principalNo(), o, c);
        refunds.create(calculated);
        return calculated;
    }

    private State calculatePreview(String buyerNo, MallSnapshot.Order o, RefundPreviewCommand c) {
        if (o.axes().payment() != OrderState.Payment.PAID
                || o.axes().refund() == OrderState.Refund.REFUNDING) throw fail("INVALID_STATE");
        var all = refunds.ownedSources(o);
        if (all.isEmpty()) return pendingPreview(buyerNo, o, c);
        if (accounts.lockSubscriptionAccount(c.store()).pendingChangeOrderNo() != null)
            throw fail("ACCOUNT_BUSY");
        var chosen =
                c.sourcePeriodNos().isEmpty()
                        ? all
                        : all.stream()
                                .filter(p -> c.sourcePeriodNos().contains(p.period().no()))
                                .toList();
        if (chosen.isEmpty()
                || !c.sourcePeriodNos().isEmpty()
                        && (new HashSet<>(c.sourcePeriodNos()).size() != c.sourcePeriodNos().size()
                                || chosen.size() != c.sourcePeriodNos().size()))
            throw fail("INVALID_SOURCE");
        var periods = subscriptions.periods(c.store());
        var codes = new TreeSet<String>();
        periods.forEach(p -> codes.addAll(p.ends().keySet()));
        var history = schedule.history(c.store(), periods, codes);
        var selected = new ArrayList<Source>();
        long at = clock.millis();
        for (var row : chosen) {
            var p = row.period();
            if (!"NORMAL".equals(p.lifecycle()) || p.duration() == null || p.revokedDuration() != 0)
                throw fail("SOURCE_NOT_REFUNDABLE");
            long used =
                    p.fixed()
                            ? Math.max(0, Math.min(p.duration(), at - p.start()))
                            : history.values().stream()
                                    .flatMap(List::stream)
                                    .filter(
                                            s ->
                                                    s.sourceNo().equals(p.no())
                                                            && s.startsAt().toEpochMilli() < at)
                                    .mapToLong(
                                            s ->
                                                    Math.max(
                                                            0,
                                                            Math.min(at, s.endsAt().toEpochMilli())
                                                                    - s.startsAt().toEpochMilli()))
                                    .reduce(0, Math::addExact);
            selected.add(
                    new Source(
                            p,
                            row.version(),
                            row.componentNo(),
                            UpgradeValue.remaining(p.cash(), p.duration(), used, p.gift()),
                            UpgradeValue.remaining(p.credit(), p.duration(), used, p.gift()),
                            used,
                            used > 0 || !RefundPolicy.parse(row.policySnapshot()).automaticUnused(),
                            row.creditSlices()));
        }
        var proof = refunds.payment(o);
        long cash = selected.stream().mapToLong(Source::cashLimit).reduce(0, Math::addExact);
        if (cash > proof.fact().paid().minor()) throw fail("REFUND_LIMIT");
        String digest =
                FactDigest.of(
                        List.of(
                                c.refundNo(),
                                o.orderNo(),
                                c.store().toString(),
                                buyerNo,
                                Long.toString(at),
                                selected.toString(),
                                proof.toString()));
        var p =
                new Preview(
                        c.refundNo(),
                        o.orderNo(),
                        c.store(),
                        o.quote().currency(),
                        buyerNo,
                        at,
                        Math.addExact(at, 900000),
                        selected,
                        history,
                        proof,
                        false,
                        digest,
                        o.axes().refund().name());
        var state = new State(p, "PREVIEW", 0, 0, null, null, null, null);
        return state;
    }

    private State pendingPreview(String buyerNo, MallSnapshot.Order o, RefundPreviewCommand c) {
        if (!c.sourcePeriodNos().isEmpty()
                || o.axes().refund() != OrderState.Refund.NONE
                || !o.orderNo()
                        .equals(accounts.lockSubscriptionAccount(c.store()).pendingChangeOrderNo()))
            throw fail("INVALID_SOURCE");
        var tasks = refunds.pendingTasks(o);
        if (tasks.isEmpty()) throw fail("INVALID_SOURCE");
        var proof = refunds.payment(o);
        long at = clock.millis();
        var p =
                new Preview(
                        c.refundNo(),
                        o.orderNo(),
                        c.store(),
                        o.quote().currency(),
                        buyerNo,
                        at,
                        Math.addExact(at, 900000),
                        List.of(),
                        Map.of(),
                        proof,
                        false,
                        FactDigest.of(
                                List.of(
                                        c.refundNo(),
                                        o.orderNo(),
                                        tasks.toString(),
                                        proof.toString(),
                                        Long.toString(at))),
                        o.axes().refund().name(),
                        tasks);
        if (p.cashLimit() > proof.fact().paid().minor()) throw fail("REFUND_LIMIT");
        var s = new State(p, "PREVIEW", 0, 0, null, null, null, null);
        return s;
    }

    public RefundView request(ActorContext actor, RefundCommand c) {
        if (c == null
                || !buyerRefundNo(c.refundNo())
                || c.cashAmount() == null
                || !text(c.reason())
                || c.reason().length() > 1024) throw fail("INVALID_REQUEST");
        return locked(
                actor == null ? null : actor.tenantNo(),
                c.orderNo(),
                o -> {
                    authorize(actor, o, false);
                    coordinates(c.store(), o);
                    var s =
                            previewLocked(
                                    actor,
                                    o,
                                    new RefundPreviewCommand(
                                            c.store(),
                                            c.orderNo(),
                                            c.refundNo(),
                                            c.sourcePeriodNos()));
                    if (!c.cashAmount().equals(s.preview().view().cashLimit()))
                        throw fail("REFUND_LIMIT");
                    if (!"PREVIEW".equals(s.status())) return s.view();
                    if (clock.millis() >= s.preview().expiresAt()) throw fail("QUOTE_EXPIRED");
                    var requested =
                            new State(s.preview(), "REQUESTED", 0, 0, null, c.reason(), null, null);
                    refunds.save(requested);
                    return requested.view();
                });
    }

    public RefundReviewQuoteView reviewQuote(ActorContext actor, RefundReviewQuoteCommand c) {
        if (c == null || !text(c.requestDigest()) || !buyerRefundNo(c.reviewQuoteNo()))
            throw fail("INVALID_REQUEST");
        return locked(actor == null ? null : actor.tenantNo(), c.orderNo(), o -> {
            authorize(actor, o, true);
            coordinates(c.store(), o);
            var request = state(o, c.refundNo());
            if (!request.requestDigest().equals(c.requestDigest())) throw fail("VERSION_CONFLICT");
            var existing = refunds.reviewQuote(actor.tenantNo(), c.refundNo(), c.reviewQuoteNo());
            if (existing.isPresent()) {
                if (!existing.get().requestDigest().equals(c.requestDigest())) throw fail("IDEMPOTENCY_CONFLICT");
                return existing.get().view();
            }
            if (!"REQUESTED".equals(request.status())) throw fail("INVALID_STATE");
            var original = request.preview();
            var current = calculatePreview(original.buyerNo(), o,
                    new RefundPreviewCommand(c.store(), c.orderNo(), c.refundNo(),
                            original.view().sourcePeriodNos())).preview();
            // An unmaterialized complete group is a distinct request intent, never a source refund.
            if (!original.pendingTasks().isEmpty() && !original.pendingTasks().equals(current.pendingTasks())
                    || original.pendingTasks().isEmpty() && !current.pendingTasks().isEmpty())
                throw fail("VERSION_CONFLICT");
            var bounded = new ArrayList<Source>();
            for (var source : current.sources()) {
                var prior = original.sources().stream()
                        .filter(v -> v.period().no().equals(source.period().no()))
                        .findFirst().orElseThrow(() -> fail("VERSION_CONFLICT"));
                bounded.add(new Source(source.period(), source.version(), source.componentNo(),
                        Math.min(prior.cashLimit(), source.cashLimit()),
                        Math.min(prior.creditLimit(), source.creditLimit()), source.usedDuration(),
                        source.manual() || prior.manual(), source.creditSlices()));
            }
            if (bounded.size() != original.sources().size()) throw fail("VERSION_CONFLICT");
            var digest = FactDigest.of(List.of(c.reviewQuoteNo(), c.requestDigest(),
                    current.digest(), bounded.toString(), current.previousRefund()));
            var quote = new ReviewQuote(c.reviewQuoteNo(), c.requestDigest(),
                    new Preview(current.refundNo(), current.orderNo(), current.store(), current.currency(),
                            original.buyerNo(), current.createdAt(), current.expiresAt(), bounded,
                            current.history(), current.payment(), false, digest,
                            current.previousRefund(), current.pendingTasks()));
            if (quote.preview().cashLimit() > original.cashLimit()
                    || quote.preview().creditLimit() > original.creditLimit()) throw fail("REFUND_LIMIT");
            refunds.createReviewQuote(quote);
            return quote.view();
        });
    }

    public RefundView reject(ActorContext actor, RefundRejectionCommand c) {
        if (c == null || !text(c.requestDigest()) || !text(c.reviewEvidence())
                || c.reviewEvidence().length() > 1024) throw fail("INVALID_REQUEST");
        return locked(actor == null ? null : actor.tenantNo(), c.orderNo(), o -> {
            authorize(actor, o, true);
            coordinates(c.store(), o);
            var s = state(o, c.refundNo());
            if (!s.requestDigest().equals(c.requestDigest())) throw fail("VERSION_CONFLICT");
            if ("DECLINED".equals(s.status())) {
                if (!actor.principalNo().equals(s.reviewer())
                        || !c.reviewEvidence().equals(s.reviewEvidence())) throw fail("IDEMPOTENCY_CONFLICT");
                return s.view();
            }
            if (!"REQUESTED".equals(s.status())) throw fail("INVALID_STATE");
            var declined = new State(s.preview(), "DECLINED", 0, 0, actor.principalNo(),
                    c.reviewEvidence(), null, null, clock.millis()).inheritReview(s);
            refunds.save(declined);
            return declined.view();
        });
    }

    public RefundView approve(ActorContext actor, RefundApprovalCommand c) {
        if (c == null
                || !text(c.reviewEvidence())
                || c.reviewEvidence().length() > 1024
                || c.cashAmount() == null
                || c.restoreCredit() == null) throw fail("INVALID_REQUEST");
        return locked(
                actor == null ? null : actor.tenantNo(),
                c.orderNo(),
                o -> {
                    authorize(actor, o, true);
                    coordinates(c.store(), o);
                    var s = state(o, c.refundNo());
                    if ("DECLINED".equals(s.status())) throw fail("INVALID_STATE");
                    if (c.reviewQuoteNo() != null) {
                        var review = refunds.reviewQuote(actor.tenantNo(), c.refundNo(), c.reviewQuoteNo())
                                .orElseThrow(() -> fail("VERSION_CONFLICT"));
                        if (!review.requestDigest().equals(s.requestDigest())) throw fail("VERSION_CONFLICT");
                        if (s.reviewQuoteNo() != null && !s.reviewQuoteNo().equals(c.reviewQuoteNo()))
                            throw fail("IDEMPOTENCY_CONFLICT");
                        s = s.withReview(review);
                    } else if (s.reviewQuoteNo() != null) throw fail("IDEMPOTENCY_CONFLICT");
                    var p = s.preview();
                    if (actor.principalNo().equals(p.buyerNo())) throw fail("FORBIDDEN");
                    if (!p.digest().equals(c.approvalDigest())
                            || !p.currency().equals(c.cashAmount().currency())
                            || !p.currency().equals(c.restoreCredit().currency())
                            || c.cashAmount().minor() < 0
                            || c.cashAmount().minor() > p.cashLimit()
                            || c.restoreCredit().minor() < 0
                            || c.restoreCredit().minor() > p.creditLimit())
                        throw fail("REFUND_LIMIT");
                    if (!"REQUESTED".equals(s.status())) {
                        if (s.approvedCash() != c.cashAmount().minor()
                                || s.restoredCredit() != c.restoreCredit().minor()
                                || !Objects.equals(s.reviewer(), actor.principalNo())
                                || !Objects.equals(s.reviewEvidence(), c.reviewEvidence()))
                            throw fail("IDEMPOTENCY_CONFLICT");
                        return s.view();
                    }
                    if (clock.millis() >= p.expiresAt()) throw fail("QUOTE_EXPIRED");
                    if (!p.pendingTasks().isEmpty()) {
                        if (o.axes().payment() != OrderState.Payment.PAID
                                || o.axes().refund() != OrderState.Refund.NONE
                                || !o.orderNo()
                                        .equals(
                                                accounts.lockSubscriptionAccount(p.store())
                                                        .pendingChangeOrderNo())
                                || !refunds.ownedSources(o).isEmpty()
                                || !refunds.pendingTasks(o).equals(p.pendingTasks()))
                            throw fail("VERSION_CONFLICT");
                        var approved =
                                approved(
                                        s,
                                        c.cashAmount().minor(),
                                        0,
                                        actor.principalNo(),
                                        c.reviewEvidence());
                        refunds.freezeTasks(approved);
                        refunds.save(approved);
                        mall.transition(
                                o,
                                new OrderState.Axes(
                                        o.axes().payment(),
                                        o.axes().fulfillment(),
                                        OrderState.Refund.REFUNDING),
                                actor.principalNo());
                        return approved.view();
                    }
                    // The rejection baseline is immutable preview evidence. Another completed
                    // source refund must invalidate it before any freeze or external dispatch.
                    if (!o.axes().refund().name().equals(p.previousRefund()))
                        throw fail("VERSION_CONFLICT");
                    if (accounts.lockSubscriptionAccount(p.store()).pendingChangeOrderNo() != null)
                        throw fail("ACCOUNT_BUSY");
                    var current = refunds.ownedSources(o);
                    for (var source : p.sources())
                        if (current.stream()
                                .noneMatch(
                                        v ->
                                                v.period().no().equals(source.period().no())
                                                        && v.version() == source.version()
                                                        && "NORMAL".equals(v.period().lifecycle())))
                            throw fail("VERSION_CONFLICT");
                    if (clock.millis() < subscriptions.lastChange(p.store()))
                        throw fail("BACKDATED_REFUND");
                    var before = subscriptions.periods(p.store());
                    var codes = p.history().keySet();
                    var history = schedule.history(p.store(), before, codes);
                    if (!history.equals(p.history())) throw fail("VERSION_CONFLICT");
                    var approved =
                            approved(
                                    s,
                                    c.cashAmount().minor(),
                                    c.restoreCredit().minor(),
                                    actor.principalNo(),
                                    c.reviewEvidence());
                    long cut = clock.millis();
                    refunds.freeze(approved, before, cut);
                    var calculation =
                            clipped(
                                    history,
                                    p.sources().stream()
                                            .map(v -> v.period().no())
                                            .collect(java.util.stream.Collectors.toSet()),
                                    cut);
                    schedule.persistTransition(
                            subscriptions.lock(p.store()),
                            codes,
                            "freeze-" + FactDigest.of(List.of(p.refundNo())).substring(0, 48),
                            Instant.ofEpochMilli(cut),
                            Set.of(),
                            history,
                            calculation,
                            actor.principalNo());
                    refunds.save(approved);
                    mall.transition(
                            o,
                            new OrderState.Axes(
                                    o.axes().payment(),
                                    o.axes().fulfillment(),
                                    OrderState.Refund.REFUNDING),
                            actor.principalNo());
                    return approved.view();
                });
    }

    private State approved(State s, long cash, long credit, String reviewer, String evidence) {
        var p = s.preview();
        var f = p.payment().fact();
        var command =
                new PaymentRefundCommand(
                        p.store().tenantNo(),
                        p.orderNo(),
                        p.refundNo(),
                        new Money(p.currency(), cash),
                        evidence,
                        p.store(),
                        p.payment().attemptNo(),
                        f.paymentNo(),
                        f.platformMerchantNo(),
                        f.paid());
        return new State(
                p, "APPROVED", cash, credit, reviewer, evidence, command, null, clock.millis()).inheritReview(s);
    }

    private static Map<String, List<AccessSegment>> clipped(
            Map<String, List<AccessSegment>> old, Set<String> sources, long cut) {
        var out = new HashMap<String, List<AccessSegment>>();
        old.forEach(
                (code, rows) -> {
                    var slices = new ArrayList<AccessSegment>();
                    for (var row : rows) {
                        if (!sources.contains(row.sourceNo())) slices.add(row);
                        else if (row.startsAt().toEpochMilli() < cut)
                            slices.add(
                                    new AccessSegment(
                                            row.sourceNo(),
                                            code,
                                            row.startsAt(),
                                            Instant.ofEpochMilli(
                                                    Math.min(cut, row.endsAt().toEpochMilli()))));
                    }
                    out.put(code, List.copyOf(slices));
                });
        return out;
    }

    public RefundView recover(RefundQuery q) {
        if (q == null) throw fail("INVALID_REQUEST");
        uow.requireOutsideTransaction();
        record Dispatch(State state, boolean fresh) {}
        var dispatch =
                locked(
                        q.tenantNo(),
                        q.orderNo(),
                        o -> {
                            var s = state(o, q.refundNo());
                            if (!Set.of("APPROVED", "DISPATCHING", "UNKNOWN").contains(s.status()))
                                return new Dispatch(s, false);
                            boolean fresh = "APPROVED".equals(s.status());
                            var next =
                                    new State(
                                            s.preview(),
                                            "DISPATCHING",
                                            s.approvedCash(),
                                            s.restoredCredit(),
                                            s.reviewer(),
                                            s.reviewEvidence(),
                                            s.command(),
                                            s.fact()).inheritReview(s);
                            refunds.save(next);
                            return new Dispatch(next, fresh);
                        });
        var s = dispatch.state();
        if (!"DISPATCHING".equals(s.status())) return s.view();
        if (s.approvedCash() == 0) {
            var c = s.command();
            return confirmRefund(
                    new VerifiedRefundFact(
                            c.tenantNo(),
                            "local:" + c.refundNo(),
                            c.orderNo(),
                            c.refundNo(),
                            c.store(),
                            c.paymentAttemptNo(),
                            c.originalPaymentNo(),
                            c.platformMerchantNo(),
                            c.originalPaid(),
                            c.amount(),
                            "local-zero-" + FactDigest.of(List.of(c.refundNo())).substring(0, 48),
                            AsyncStatus.SUCCEEDED,
                            true,
                            clock.instant(),
                            "local-zero-settlement"));
        }
        PaymentRefundOutcome result;
        try {
            uow.requireOutsideTransaction();
            result =
                    dispatch.fresh()
                            ? payment.refund(s.command())
                            : payment.queryRefund(
                                    new PaymentRefundQuery(
                                            q.tenantNo(), q.orderNo(), q.refundNo()));
        } catch (RuntimeException lost) {
            result = null;
        }
        if (result != null
                && q.refundNo().equals(result.refundNo())
                && result.verifiedFact() != null
                && result.status() == result.verifiedFact().status()) {
            var fact = result.verifiedFact();
            if (!q.tenantNo().equals(fact.tenantNo())
                    || !q.orderNo().equals(fact.orderNo())
                    || !q.refundNo().equals(fact.refundNo())) throw fail("REFUND_FACT_MISMATCH");
            return confirmRefund(fact);
        }
        return locked(
                q.tenantNo(),
                q.orderNo(),
                o -> {
                    var latest = state(o, q.refundNo());
                    if (Set.of("SUCCEEDED", "REJECTED").contains(latest.status()))
                        return latest.view();
                    var unknown =
                            new State(
                                    latest.preview(),
                                    "UNKNOWN",
                                    latest.approvedCash(),
                                    latest.restoredCredit(),
                                    latest.reviewer(),
                                    latest.reviewEvidence(),
                                    latest.command(),
                                    null).inheritReview(latest);
                    refunds.save(unknown);
                    return unknown.view();
                });
    }

    public RefundView confirmRefund(VerifiedRefundFact fact) {
        if (fact == null) throw fail("INVALID_FACT");
        return locked(
                fact.tenantNo(),
                fact.orderNo(),
                o -> {
                    var s = state(o, fact.refundNo());
                    var c = s.command();
                    if (c == null
                            || !fact.definitiveFinal()
                            || !Set.of(AsyncStatus.SUCCEEDED, AsyncStatus.FAILED)
                                    .contains(fact.status())
                            || !text(fact.eventNo())
                            || !text(fact.verificationReference())
                            || !text(fact.providerRefundNo())
                            || fact.providerRefundNo().length() > 64
                            || fact.observedAt() == null
                            || !Objects.equals(c.store(), fact.store())
                            || !Objects.equals(c.paymentAttemptNo(), fact.paymentAttemptNo())
                            || !Objects.equals(c.originalPaymentNo(), fact.originalPaymentNo())
                            || !Objects.equals(c.platformMerchantNo(), fact.platformMerchantNo())
                            || !Objects.equals(c.originalPaid(), fact.originalPaid())
                            || !Objects.equals(c.amount(), fact.refunded()))
                        throw fail("REFUND_FACT_MISMATCH");
                    if (Set.of("SUCCEEDED", "REJECTED").contains(s.status())) {
                        if (("SUCCEEDED".equals(s.status()))
                                != (fact.status() == AsyncStatus.SUCCEEDED))
                            throw fail("CONTRADICTORY_REFUND_FACT");
                        return s.view();
                    }
                    if (!Set.of("APPROVED", "DISPATCHING", "UNKNOWN").contains(s.status()))
                        throw fail("INVALID_STATE");
                    boolean success = fact.status() == AsyncStatus.SUCCEEDED;
                    var finalState =
                            new State(
                                    s.preview(),
                                    success ? "SUCCEEDED" : "REJECTED",
                                    s.approvedCash(),
                                    s.restoredCredit(),
                                    s.reviewer(),
                                    s.reviewEvidence(),
                                    c,
                                    fact).inheritReview(s);
                    if (s.preview().late()) {
                        if (success && refunds.hasReservedHold(o))
                            fulfillment.settleHolds(o, false);
                    } else if (!s.preview().pendingTasks().isEmpty()) {
                        refunds.resolveTasks(finalState, success);
                        if (success) fulfillment.settleHolds(o, false);
                    } else {
                        var p = s.preview();
                        var before = subscriptions.periods(p.store());
                        var codes = p.history().keySet();
                        var history = schedule.history(p.store(), before, codes);
                        if (success) refunds.complete(finalState);
                        else {
                            refunds.restore(finalState);
                            schedule.persistTransition(
                                    subscriptions.lock(p.store()),
                                    codes,
                                    "restore-"
                                            + FactDigest.of(List.of(p.refundNo())).substring(0, 48),
                                    clock.instant(),
                                    Set.of(),
                                    history,
                                    p.history(),
                                    s.reviewer());
                        }
                    }
                    refunds.save(finalState);
                    var refund =
                            success
                                    ? s.preview().late() || refunds.fullyRefunded(o)
                                            ? OrderState.Refund.REFUNDED
                                            : OrderState.Refund.PARTIAL
                                    : s.preview().late()
                                            ? OrderState.Refund.REFUNDING
                                            : OrderState.Refund.valueOf(
                                                    s.preview().previousRefund());
                    mall.transition(
                            o,
                            new OrderState.Axes(o.axes().payment(), o.axes().fulfillment(), refund),
                            s.reviewer());
                    return finalState.view();
                });
    }

    public RefundView recoverLatePayment(FulfillmentQuery query) {
        if (query == null) throw fail("INVALID_REQUEST");
        uow.requireOutsideTransaction();
        String prefix =
                "late-"
                        + FactDigest.of(List.of(query.tenantNo(), query.orderNo()))
                                .substring(0, 40);
        String no =
                locked(
                        query.tenantNo(),
                        query.orderNo(),
                        o -> {
                            var latest =
                                    refunds.latestLatePayment(
                                            query.tenantNo(), query.orderNo(), prefix);
                            if (latest.isPresent() && !"REJECTED".equals(latest.get().status()))
                                return latest.get().preview().refundNo();
                            int attempt =
                                    latest.isEmpty()
                                            ? 0
                                            : Math.incrementExact(
                                                    Integer.parseInt(
                                                            latest.get()
                                                                    .preview()
                                                                    .refundNo()
                                                                    .substring(
                                                                            prefix.length() + 1)));
                            String candidate = prefix + "-" + attempt;
                            if (o.axes().payment() != OrderState.Payment.PAID
                                    || o.axes().fulfillment() != OrderState.Fulfillment.FAILED
                                    || o.axes().refund() != OrderState.Refund.REFUNDING
                                    || !refunds.ownedSources(o).isEmpty())
                                throw fail("INVALID_STATE");
                            var proof = refunds.payment(o);
                            long at = clock.millis();
                            var p =
                                    new Preview(
                                            candidate,
                                            o.orderNo(),
                                            o.quote().store(),
                                            o.quote().currency(),
                                            o.quote().buyerNo(),
                                            at,
                                            Long.MAX_VALUE,
                                            List.of(),
                                            Map.of(),
                                            proof,
                                            true,
                                            FactDigest.of(List.of(candidate, proof.toString())),
                                            o.axes().refund().name());
                            refunds.create(
                                    approved(
                                            new State(p, "REQUESTED", 0, 0, null, null, null, null),
                                            proof.fact().paid().minor(),
                                            0,
                                            "TRUSTED_LATE_PAYMENT",
                                            "verified payment has no deliverable rights"));
                            return candidate;
                        });
        return recover(new RefundQuery(query.tenantNo(), query.orderNo(), no));
    }
}
