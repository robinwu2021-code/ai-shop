package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.mapper.OrderPointsPlacementMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService.*;
import ai.neargo.shop.trade.order.refund.policy.LineShares;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.BiFunction;

@Service
public class PointsOrderPlacementCoordinatorImpl implements PointsOrderPlacementCoordinator {
    private final OrderPointsPlacementMapper mapper;
    private final OrderPointsBindingService bindings;
    private final ObjectProvider<SourceOrders> orders;
    private final ObjectProvider<PointsRedeemPort> redeem;
    private final ObjectProvider<PointsOrderTermsPort> terms;
    private final ObjectProvider<OrderPointsFactService> journal;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public PointsOrderPlacementCoordinatorImpl(
            OrderPointsPlacementMapper mapper,
            OrderPointsBindingService bindings,
            ObjectProvider<SourceOrders> orders,
            ObjectProvider<PointsRedeemPort> redeem,
            ObjectProvider<PointsOrderTermsPort> terms,
            ObjectProvider<OrderPointsFactService> journal,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.bindings = bindings;
        this.orders = orders;
        this.redeem = redeem;
        this.terms = terms;
        this.journal = journal;
        this.tx = new TransactionTemplate(manager);
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Placement stage(Draft draft) {
        require(
                draft != null
                        && nonblank(draft.orderNo())
                        && nonblank(draft.userNo())
                        && nonblank(draft.requestHash())
                        && draft.wantPoints() >= 0
                        && draft.basePayMinor() >= 0
                        && !draft.candidates().isEmpty(),
                "POINTS_PLACEMENT_INPUT_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var root = source().lock(draft.orderNo());
                    var prior = decode(mapper.lock(draft.orderNo()));
                    if (prior != null) {
                        require(prior.draft().equals(draft), "POINTS_PLACEMENT_CONFLICT");
                        return prior;
                    }
                    require(
                            root != null
                                    && "POINTS_PENDING".equals(root.status())
                                    && draft.userNo().equals(root.userNo())
                                    && draft.currency().equals(root.currency())
                                    && draft.basePayMinor() == root.payMinor(),
                            "POINTS_PLACEMENT_SOURCE_CONFLICT");
                    var state = new Placement(draft, Phase.RAW, 0, null, null, null);
                    mapper.create(draft.orderNo(), json.writeValueAsString(state));
                    return state;
                });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public Placement inspect(String order) {
        return DataScopeContext.executeWithoutScope(() -> decode(mapper.find(order)));
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public List<String> pending(int limit) {
        require(limit >= 1 && limit <= 200, "POINTS_PLACEMENT_LIMIT_INVALID");
        return DataScopeContext.executeWithoutScope(() -> mapper.pending(limit));
    }

    @Override
    public Placement resume(String order) {
        require(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "POINTS_SOURCE_TRANSACTION_OPEN");
        var state = inspect(order);
        if (state == null
                || Set.of(Phase.READY, Phase.CANCELLED, Phase.RECONFIRM).contains(state.phase()))
            return state;
        if (state.phase() == Phase.CANCEL_REQUESTED) return release(state);
        if (state.phase() == Phase.RAW) {
            Resolved resolved;
            try {
                resolved = resolve(state.draft());
            } catch (IllegalArgumentException rejected) {
                if (reconfirm(rejected.getMessage()))
                    return mark(order, Phase.RECONFIRM, rejected.getMessage(), null);
                throw rejected;
            }
            try {
                state =
                        locked(
                                order,
                                (root, current) -> {
                                    if (current.phase() != Phase.RAW) return current;
                                    requirePending(root, current);
                                    resolved.admissions().stream()
                                            .sorted(
                                                    Comparator.comparing(Admission::storeNo)
                                                            .thenComparing(Admission::subOrderNo))
                                            .forEach(bindings::admit);
                                    return change(current, Phase.ADMITTED, resolved, null, null);
                                });
            } catch (IllegalArgumentException rejected) {
                if (reconfirm(rejected.getMessage()))
                    return mark(order, Phase.RECONFIRM, rejected.getMessage(), null);
                throw rejected;
            }
        }
        if (state.phase() == Phase.CANCEL_REQUESTED) return release(state);
        if (state.phase() != Phase.ADMITTED && state.phase() != Phase.UNKNOWN) return state;
        var resolved = state.resolved();
        require(resolved != null, "POINTS_PLACEMENT_RESOLUTION_REQUIRED");
        PointsRedeemPort.HoldOutcome outcome;
        if (resolved.command() == null) {
            var q = resolved.quote();
            outcome =
                    new PointsRedeemPort.HoldOutcome(
                            null, q.typeCode(), 0, 0, "SKIPPED", null, List.of());
        } else {
            var port = redeem.getIfAvailable();
            if (port == null)
                return mark(order, Phase.UNKNOWN, "POINTS_IMPLEMENTATION_REQUIRED", null);
            try {
                outcome = port.hold(resolved.command());
            } catch (IllegalArgumentException rejected) {
                if (reconfirm(rejected.getMessage()))
                    return mark(order, Phase.RECONFIRM, rejected.getMessage(), null);
                throw rejected;
            } catch (RuntimeException unavailable) {
                return mark(order, Phase.UNKNOWN, "POINTS_HOLD_UNKNOWN", null);
            }
        }
        var quote = resolved.quote();
        if (outcome == null
                || !Objects.equals(outcome.typeCode(), quote.typeCode())
                || outcome.points() != quote.points()
                || outcome.minor() != quote.minor()
                || !outcome.shares().equals(quote.shares()))
            return mark(order, Phase.RECONFIRM, "POINTS_REQUOTE_REQUIRED", outcome);
        var confirmed = outcome;
        return locked(
                order,
                (root, current) -> {
                    if (current.phase() == Phase.READY || current.phase() == Phase.CANCELLED)
                        return current;
                    if (current.phase() == Phase.CANCEL_REQUESTED)
                        return change(
                                current,
                                Phase.CANCEL_REQUESTED,
                                current.resolved(),
                                confirmed,
                                current.reason());
                    requirePending(root, current);
                    require(
                            current.resolved().equals(resolved),
                            "POINTS_PLACEMENT_RESOLUTION_CONFLICT");
                    for (var admission : resolved.admissions()) {
                        var binding = bindings.find(admission.subOrderNo());
                        require(binding != null, "POINTS_BINDING_UNKNOWN");
                        if (binding.phase() == OrderPointsBindingService.Phase.PENDING)
                            bindings.transition(
                                    new Transition(
                                            binding.bindingNo(),
                                            binding.version(),
                                            OrderPointsBindingService.Phase.BOUND,
                                            admission.redemption() ? confirmed.holdNo() : null));
                    }
                    source().finish(current.draft(), confirmed);
                    return change(current, Phase.READY, current.resolved(), confirmed, null);
                });
    }

    private Resolved resolve(Draft d) {
        var provider = terms.getIfAvailable();
        require(provider != null, "POINTS_IMPLEMENTATION_REQUIRED");
        Map<String, PointsOrderTermsPort.Terms> bySub = new HashMap<>();
        List<PointsRedeemPort.Target> targets = new ArrayList<>();
        PointsOrderTermsPort.Terms first = null;
        for (var candidate : d.candidates()) {
            var target = candidate.target().target();
            var value =
                    provider.resolve(
                                    d.market(),
                                    d.userNo(),
                                    target.merchantNo(),
                                    target.storeNo(),
                                    d.scene(),
                                    d.client())
                            .orElse(null);
            require(value != null, "POINTS_TERMS_CHANGED");
            require(d.currency().equals(value.currency()), "POINTS_TERMS_CHANGED");
            if (first == null) first = value;
            else
                require(
                        first.typeCode().equals(value.typeCode())
                                && first.ratePoints() == value.ratePoints()
                                && first.rateMinor() == value.rateMinor(),
                        "POINTS_TERMS_CHANGED");
            bySub.put(target.subOrderNo(), value);
            if (d.wantPoints() > 0 && candidate.redemption() && value.redemptionAllowed())
                targets.add(target);
        }
        PointsRedeemPort.RedeemQuote quote;
        if (d.wantPoints() > 0) {
            require(
                    !targets.isEmpty() && redeem.getIfAvailable() != null,
                    "POINTS_REQUOTE_REQUIRED");
            quote =
                    redeem.getObject()
                            .quote(
                                    new PointsRedeemPort.RedeemQuery(
                                            d.market(),
                                            d.userNo(),
                                            d.wantPoints(),
                                            d.scene(),
                                            d.client(),
                                            targets));
            require(
                    quote != null
                            && first != null
                            && first.typeCode().equals(quote.typeCode())
                            && first.ratePoints() == quote.pointsUnit()
                            && first.rateMinor() == quote.minorUnit()
                            && quote.points() > 0
                            && quote.minor() > 0
                            && quote.minor() <= d.basePayMinor(),
                    "POINTS_REQUOTE_REQUIRED");
        } else
            quote =
                    new PointsRedeemPort.RedeemQuote(
                            first == null ? null : first.typeCode(),
                            first == null ? 0 : first.ratePoints(),
                            first == null ? 0 : first.rateMinor(),
                            0,
                            0,
                            null,
                            List.of());
        Map<String, PointsRedeemPort.Share> shares = new HashMap<>();
        long points = 0, minor = 0;
        for (var share : quote.shares()) {
            require(
                    share != null
                            && share.points() >= 0
                            && share.minor() >= 0
                            && shares.put(share.subOrderNo(), share) == null,
                    "POINTS_REQUOTE_REQUIRED");
            var target =
                    targets.stream()
                            .filter(t -> t.subOrderNo().equals(share.subOrderNo()))
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalArgumentException("POINTS_REQUOTE_REQUIRED"));
            require(
                    target.storeNo().equals(share.storeNo())
                            && target.merchantNo().equals(share.merchantNo())
                            && share.minor() <= target.goodsMinor(),
                    "POINTS_REQUOTE_REQUIRED");
            points = Math.addExact(points, share.points());
            minor = Math.addExact(minor, share.minor());
        }
        require(points == quote.points() && minor == quote.minor(), "POINTS_REQUOTE_REQUIRED");
        List<Admission> admissions = new ArrayList<>();
        for (var candidate : d.candidates()) {
            var target = candidate.target().target();
            var value = bySub.get(target.subOrderNo());
            if (value == null) continue;
            var share = shares.get(target.subOrderNo());
            long used = share == null ? 0 : share.points(),
                    deduct = share == null ? 0 : share.minor();
            boolean earning = candidate.earning() && value.earningAllowed();
            if (!earning && used == 0) continue;
            var payment = candidate.target().payment();
            var acceptedPayment =
                    new PointsRefundPort.PaymentSnapshot(
                            payment.fundsPath(),
                            payment.channel(),
                            payment.merchantNo(),
                            payment.storeNo(),
                            payment.accountVersion(),
                            payment.subMerchantId(),
                            null,
                            null,
                            d.basePayMinor() - quote.minor());
            var rules = freezeRules(candidate, value, deduct);
            var snapshot =
                    new Snapshot(
                            target.goodsMinor(),
                            used,
                            deduct,
                            value.ratePoints(),
                            value.rateMinor(),
                            json.writeValueAsString(rules),
                            value.agreementSnapshot(),
                            acceptedPayment,
                            target.items());
            admissions.add(
                    new Admission(
                            "ORDER:" + target.subOrderNo(),
                            d.orderNo(),
                            target.subOrderNo(),
                            d.userNo(),
                            target.merchantNo(),
                            target.storeNo(),
                            value.typeCode(),
                            d.currency(),
                            d.scene(),
                            candidate.policyVersion(),
                            earning,
                            used > 0,
                            snapshot));
        }
        var expected =
                new PointsRedeemPort.AcceptedPrice(
                        quote.typeCode(),
                        quote.pointsUnit(),
                        quote.minorUnit(),
                        quote.points(),
                        quote.minor(),
                        quote.shares());
        var command =
                quote.points() == 0
                        ? null
                        : new PointsRedeemPort.HoldCommand(
                                d.orderNo(),
                                d.market(),
                                d.userNo(),
                                d.wantPoints(),
                                d.scene(),
                                d.client(),
                                targets,
                                expected);
        return new Resolved(admissions, quote, command);
    }

    private EarningSnapshot freezeRules(
            Candidate c, PointsOrderTermsPort.Terms terms, long deduct) {
        var items = c.target().target().items();
        Map<String, PointsEarnPort.EarnLine> supplied = new HashMap<>();
        for (var line : c.rules())
            require(
                    line != null && supplied.put(line.itemId(), line) == null,
                    "POINTS_PLACEMENT_LINES_INVALID");
        require(supplied.size() == items.size(), "POINTS_PLACEMENT_LINES_INVALID");
        long[] deductions =
                LineShares.allocate(
                        deduct,
                        items.stream().mapToLong(PointsRedeemPort.Item::goodsMinor).toArray());
        List<PointsEarnPort.EarnLine> frozen = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var line = supplied.get(item.itemId());
            require(
                    line != null
                            && line.quantity() > 0
                            && line.goodsCashMinor() == item.goodsMinor()
                            && line.refundedBeforeGrantMinor() == 0,
                    "POINTS_PLACEMENT_LINES_INVALID");
            frozen.add(
                    new PointsEarnPort.EarnLine(
                            line.itemId(),
                            line.goodsNo(),
                            line.categoryNo(),
                            line.quantity(),
                            item.goodsMinor() - deductions[i],
                            0,
                            line.rule() == null ? terms.fallback() : line.rule()));
        }
        return new EarningSnapshot(1, terms.fallbackRevision(), frozen);
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public void recordCancellation(String order) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    var root = source().lock(order);
                    var current = decode(mapper.lock(order));
                    if (current != null) cancel(root, current);
                    return null;
                });
    }

    private Placement cancel(SourceOrder root, Placement current) {
        require(
                root != null && Set.of("CANCELLED", "CLOSED").contains(root.status()),
                "POINTS_CANCELLATION_PROOF_REQUIRED");
        if (current.phase() == Phase.CANCELLED) return current;
        if (current.phase() == Phase.CANCEL_REQUESTED) return current;
        if (current.resolved() == null) return change(current, Phase.CANCELLED, null, null, null);
        var facts = journal.getIfAvailable();
        require(facts != null, "POINTS_SOURCE_JOURNAL_REQUIRED");
        var admissions =
                current.resolved().admissions().stream()
                        .sorted(
                                Comparator.comparing(Admission::typeCode)
                                        .thenComparing(Admission::subOrderNo))
                        .toList();
        for (var a : admissions) {
            var b = bindings.find(a.subOrderNo());
            require(b != null, "POINTS_BINDING_UNKNOWN");
            if (b.phase() == OrderPointsBindingService.Phase.PENDING
                    || b.phase() == OrderPointsBindingService.Phase.BOUND)
                bindings.transition(
                        new Transition(
                                b.bindingNo(),
                                b.version(),
                                OrderPointsBindingService.Phase.CLOSING,
                                b.holdNo()));
            String event = "ORDER:CANCELLED:" + a.subOrderNo();
            if (facts.find(event).isPresent()) continue;
            var earning = json.readValue(a.snapshot().ruleSnapshot(), EarningSnapshot.class);
            Map<String, Long> quantities = new HashMap<>();
            for (var line : earning.lines()) quantities.put(line.itemId(), line.quantity());
            var lines =
                    a.snapshot().items().stream()
                            .map(
                                    i ->
                                            new PointsFactPort.LineSnapshot(
                                                    i.itemId(),
                                                    Objects.requireNonNull(
                                                            quantities.get(i.itemId())),
                                                    i.goodsMinor(),
                                                    0))
                            .toList();
            var raw =
                    new PointsFactPort.LifecycleFact(
                            event,
                            null,
                            a.subOrderNo(),
                            a.userNo(),
                            a.typeCode(),
                            root.version(),
                            PointsFactPort.FactKind.CANCELLED,
                            a.snapshot().goodsMinor(),
                            0,
                            lines,
                            root.changedAt());
            var signed = PointsLifecycleFacts.sign(raw, json::writeValueAsString);
            facts.append(
                    new PointsOrderEvent(
                            a.orderNo(),
                            a.subOrderNo(),
                            a.merchantNo(),
                            a.storeNo(),
                            a.userNo(),
                            a.typeCode(),
                            a.currency(),
                            root.version(),
                            root.changedAt(),
                            new SourceFactsPort.Fact(0, signed, null, null, null, null)));
        }
        var resolved = current.resolved();
        var cancellation =
                resolved.command() == null
                        ? null
                        : new PointsRedeemPort.ReleaseCommand(
                                current.draft().orderNo(),
                                "ORDER:CANCEL:" + current.draft().orderNo(),
                                root.version());
        var frozen =
                new Resolved(
                        resolved.admissions(), resolved.quote(), resolved.command(), cancellation);
        return change(
                current,
                Phase.CANCEL_REQUESTED,
                frozen,
                current.outcome(),
                "POINTS_CANCEL_PENDING");
    }

    private Placement release(Placement current) {
        var resolved = current.resolved();
        if (resolved == null) return current;
        if (resolved.command() != null) {
            var port = redeem.getIfAvailable();
            if (port == null) return current;
            require(resolved.cancellationCommand() != null, "POINTS_CANCELLATION_PROOF_REQUIRED");
            try {
                port.release(resolved.cancellationCommand());
            } catch (RuntimeException unavailable) {
                return current;
            }
        }
        try {
            return locked(
                    current.draft().orderNo(),
                    (root, state) -> {
                        if (state.phase() != Phase.CANCEL_REQUESTED) return state;
                        for (var a : resolved.admissions()) {
                            var binding = bindings.find(a.subOrderNo());
                            if (binding.phase() == OrderPointsBindingService.Phase.CLOSED) continue;
                            bindings.transition(
                                    new Transition(
                                            binding.bindingNo(),
                                            binding.version(),
                                            OrderPointsBindingService.Phase.CLOSED,
                                            binding.holdNo()));
                        }
                        return change(
                                state, Phase.CANCELLED, state.resolved(), state.outcome(), null);
                    });
        } catch (IllegalArgumentException open) {
            if ("POINTS_BINDING_OBLIGATIONS_OPEN".equals(open.getMessage()))
                return inspect(current.draft().orderNo());
            throw open;
        }
    }

    private Placement locked(String order, BiFunction<SourceOrder, Placement, Placement> action) {
        return tx.execute(
                status ->
                        DataScopeContext.executeWithoutScope(
                                () -> {
                                    var root = source().lock(order);
                                    var state = decode(mapper.lock(order));
                                    require(state != null, "POINTS_PLACEMENT_UNKNOWN");
                                    return action.apply(root, state);
                                }));
    }

    private Placement mark(
            String order, Phase phase, String reason, PointsRedeemPort.HoldOutcome outcome) {
        return locked(
                order,
                (root, state) -> {
                    if (Set.of(Phase.READY, Phase.CANCELLED, Phase.CANCEL_REQUESTED)
                            .contains(state.phase())) return state;
                    return change(
                            state,
                            phase,
                            state.resolved(),
                            outcome == null ? state.outcome() : outcome,
                            reason);
                });
    }

    private Placement change(
            Placement old,
            Phase phase,
            Resolved resolved,
            PointsRedeemPort.HoldOutcome outcome,
            String reason) {
        var next = new Placement(old.draft(), phase, old.version() + 1, resolved, outcome, reason);
        require(
                mapper.change(
                                old.draft().orderNo(),
                                old.version(),
                                phase.name(),
                                json.writeValueAsString(next))
                        == 1,
                "POINTS_PLACEMENT_REVISION_CONFLICT");
        return next;
    }

    private void requirePending(SourceOrder root, Placement state) {
        require(
                root != null
                        && "POINTS_PENDING".equals(root.status())
                        && root.userNo().equals(state.draft().userNo())
                        && root.currency().equals(state.draft().currency())
                        && root.payMinor() == state.draft().basePayMinor(),
                "POINTS_PLACEMENT_SOURCE_CONFLICT");
    }

    private SourceOrders source() {
        var value = orders.getIfAvailable();
        require(value != null, "POINTS_SOURCE_ORDERS_REQUIRED");
        return value;
    }

    private Placement decode(String body) {
        return body == null ? null : json.readValue(body, Placement.class);
    }

    private static boolean reconfirm(String code) {
        return Set.of(
                        "POINTS_REQUOTE_REQUIRED",
                        "POINTS_ADMISSION_CLOSED",
                        "POINTS_ADMISSION_REVISION_CONFLICT",
                        "POINTS_TERMS_CHANGED")
                .contains(code == null ? "" : code);
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
}
