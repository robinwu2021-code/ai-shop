package ai.neargo.shop.trade.order.service.create.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.ReleaseView;
import ai.neargo.shop.spi.product.StockDispositionPort.Reservation;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationLookup;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationView;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.order.service.create.CheckoutQuoteGuard;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlacementService;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlan;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlan.CompensationEvidence;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlan.PreparedCommand;
import ai.neargo.shop.trade.order.service.create.CounterPurchaseAuthorization;
import ai.neargo.shop.trade.order.service.create.ExpectedCheckoutQuote;
import ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService;
import ai.neargo.shop.trade.policy.ManualServicePurchasePolicy;
import ai.neargo.shop.trade.service.CheckoutOrderWriter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Operation first, stable original intent, external reserve, then the common writer in short Core
 * roots.
 */
@Service
public class CounterOrderPlacementServiceImpl implements CounterOrderPlacementService {
    private final CheckoutMappers.OperationMapper operations;
    private final CheckoutMappers.QuoteMapper quotes;
    private final CheckoutMappers.ConfirmationMapper confirmations;
    private final CreateIntentMapper intents;
    private final OrderMapper orders;
    private final ManualServicePurchasePolicy policy;
    private final CheckoutOrderWriter writer;
    private final CheckoutQuoteGuard guard;
    private final OrderCreationRecoveryService recovery;
    private final StockDispositionPort stock;
    private final ObjectMapper json;
    private final CheckoutClock clock;
    private final TransactionTemplate writes;

    public CounterOrderPlacementServiceImpl(
            CheckoutMappers.OperationMapper operations,
            CheckoutMappers.QuoteMapper quotes,
            CheckoutMappers.ConfirmationMapper confirmations,
            CreateIntentMapper intents,
            OrderMapper orders,
            ManualServicePurchasePolicy policy,
            CheckoutOrderWriter writer,
            CheckoutQuoteGuard guard,
            OrderCreationRecoveryService recovery,
            StockDispositionPort stock,
            ObjectMapper json,
            CheckoutClock clock,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.operations = operations;
        this.quotes = quotes;
        this.confirmations = confirmations;
        this.intents = intents;
        this.orders = orders;
        this.policy = policy;
        this.writer = writer;
        this.guard = guard;
        this.recovery = recovery;
        this.stock = stock;
        this.json = json;
        this.clock = clock;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public CheckoutResult place(CheckoutScope scope, PlaceCounterReq request) {
        try {
            return placeOwned(scope, request);
        } catch (BizException failure) {
            if (failure.errorCode() != ErrorCode.CHECKOUT_QUOTE_STALE
                    && failure.errorCode() != ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION) {
                throw failure;
            }
            var compensating =
                    markCompensating(
                            request.operation().operationNo(), failure.errorCode().name(), false);
            if (compensating != null) {
                releaseCompensating(compensating);
            }
            var op =
                    unscoped(
                            () ->
                                    operations.find(
                                            request.operation().caller(),
                                            request.operation().operationNo()));
            requireScope(op, scope);
            return result(op, "CONFIRMED".equals(op.getPhase()));
        }
    }

    private CheckoutResult placeOwned(CheckoutScope scope, PlaceCounterReq request) {
        outside();
        var actor =
                policy.requireCounterCashier(
                        scope, "COUNTER_PLACE", request.operation().operationNo(), request);
        var hint =
                unscoped(
                        () ->
                                operations.find(
                                        request.operation().caller(),
                                        request.operation().operationNo()));
        requireScope(hint, scope);
        // The accepted original owns idempotency across currently authorized cashier handovers.
        String key =
                CheckoutDigests.hash(
                        json,
                        List.of(
                                "COUNTER_PLACE_KEY_V1",
                                hint.getAuthorityNamespaceHash(),
                                request.operation().operationNo(),
                                request.requestKey()));
        if (!"CREATED".equals(hint.getPhase())) {
            policy.requireBusiness(scope);
        }
        String payload =
                CheckoutDigests.hash(
                        json,
                        Arrays.asList("COUNTER_PLACE_V1", request.confirmationNo(), party(hint)));
        // Even a stale nonlocking hint is followed by the operation-only CREATED fast path.
        var stage =
                unscoped(
                        () ->
                                writes.execute(
                                        tx -> {
                                            var op =
                                                    operations.lock(
                                                            request.operation().caller(),
                                                            request.operation().operationNo());
                                            requireScope(op, scope);
                                            requirePlace(op, request, key, payload);
                                            if (List.of("CREATED", "REQUOTE_REQUIRED", "REJECTED")
                                                    .contains(op.getPhase())) {
                                                return new Stage(op, null, false);
                                            }
                                            var authorization = authorization(op, request);
                                            if (op.getPlaceKeyHash() == null) {
                                                op.setPlaceKeyHash(key);
                                                op.setPlacePayloadHash(payload);
                                                changed(operations.updateById(op));
                                            }
                                            var existing =
                                                    recovery.findCounter(op.getOperationNo());
                                            CounterOrderPlan plan;
                                            if (existing == null) {
                                                var command =
                                                        json.readValue(
                                                                op.getCommandJson(),
                                                                PreparedCommand.class);
                                                String intentKey =
                                                        "COUNTER_ORDER_V1:"
                                                                + CheckoutDigests.hash(
                                                                        json,
                                                                        List.of(
                                                                                "MAIN",
                                                                                op.getCaller(),
                                                                                op
                                                                                        .getOperationNo()));
                                                plan =
                                                        writer.stageCounterConfirmedFor(
                                                                party(op),
                                                                command.command(),
                                                                intentKey,
                                                                authorization);
                                            } else {
                                                plan = requirePlan(existing, op, authorization);
                                            }
                                            var intent = intents.lock(plan.orderNo());
                                            requirePlan(intent, op, authorization);
                                            boolean dispatch =
                                                    "NOT_SENT".equals(intent.getReservationState())
                                                            || "UNKNOWN"
                                                                            .equals(
                                                                                    intent
                                                                                            .getReservationState())
                                                                    && intent.getNextAttemptAt()
                                                                            <= System
                                                                                    .currentTimeMillis();
                                            if (dispatch) {
                                                intent.setReservationState("UNKNOWN");
                                                intent.setNextAttemptAt(
                                                        System.currentTimeMillis() + 30_000L);
                                                changed(intents.updateById(intent));
                                            }
                                            return new Stage(op, plan, dispatch);
                                        }));
        if (stage.plan() == null) {
            return result(stage.operation(), false);
        }
        var original = original(stage.plan());
        var intent = recovery.findCounter(request.operation().operationNo());
        if (!"RESERVED".equals(intent.getReservationState())
                && !"REJECTED".equals(intent.getReservationState())) {
            var observation =
                    reserveOrObserve(
                            original, intent.getReservationOperationNo(), stage.dispatch());
            try {
                recovery.recordCounterReservation(
                        intent.getPayNo(), intent.getVersion(), observation);
            } catch (BizException collision) {
                if (collision.errorCode() != ErrorCode.CONFLICT) {
                    throw collision;
                }
                // A concurrent observer may already have advanced the same original evidence.
            }
        }
        if (!actor.equals(
                policy.requireCounterCashier(
                        scope, "COUNTER_PLACE", request.operation().operationNo(), request))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        policy.requireBusiness(scope);
        return unscoped(
                () ->
                        writes.execute(
                                tx -> {
                                    var op =
                                            operations.lock(
                                                    request.operation().caller(),
                                                    request.operation().operationNo());
                                    requireScope(op, scope);
                                    requirePlace(op, request, key, payload);
                                    if ("CREATED".equals(op.getPhase())) {
                                        return result(op, false);
                                    }
                                    var authorization = authorization(op, request);
                                    var current = intents.lock(stage.plan().orderNo());
                                    var plan = requirePlan(current, op, authorization);
                                    if (!OrdCreateIntent.PREPARED.equals(current.getStatus())
                                            || !"RESERVED".equals(current.getReservationState())
                                            || current.getReservationObservationJson() == null) {
                                        return result(op, true);
                                    }
                                    var observation =
                                            json.readValue(
                                                    current.getReservationObservationJson(),
                                                    StockDispositionPort.ReservationView.class);
                                    if (!original(plan).equals(observation.original())
                                            || !"RESERVED".equals(observation.outcome())
                                            || !current.getReservationOperationNo()
                                                    .equals(observation.reservationOperationNo())
                                            || !current.getReservationObservationHash()
                                                    .equals(observation.observationHash())) {
                                        throw BizException.of(ErrorCode.CONFLICT);
                                    }
                                    // A committed parent prevents any new write; read it without
                                    // reversing receipt's root order.
                                    if (parentExists(plan.orderNo())) {
                                        return result(op, true);
                                    }
                                    var created =
                                            writer.createCounterConfirmedFor(
                                                    plan.party(), plan, authorization);
                                    if (!plan.orderNo().equals(created.payOrderNo())
                                            || created.subOrders().size() != 1
                                            || !plan.subOrderNo()
                                                    .equals(
                                                            created.subOrders()
                                                                    .getFirst()
                                                                    .orderNo())) {
                                        throw BizException.of(ErrorCode.CONFLICT);
                                    }
                                    guard.completeCounter(
                                            authorization, plan.orderNo(), plan.subOrderNo());
                                    current.setStatus(OrdCreateIntent.COMMITTED);
                                    changed(intents.updateById(current));
                                    return result(
                                            operations.find(op.getCaller(), op.getOperationNo()),
                                            false);
                                }));
    }

    @Override
    public int recoverPending(int limit) {
        outside();
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("counter recovery limit");
        }
        var pending =
                unscoped(
                        () ->
                                intents.selectList(
                                        Wrappers.<OrdCreateIntent>lambdaQuery()
                                                .eq(OrdCreateIntent::getTenantNo, "MAIN")
                                                .eq(OrdCreateIntent::getCreationKind, "COUNTER")
                                                .in(
                                                        OrdCreateIntent::getStatus,
                                                        OrdCreateIntent.PREPARED,
                                                        OrdCreateIntent.RECOVERY_REQUIRED,
                                                        OrdCreateIntent.COMPENSATING)
                                                .le(
                                                        OrdCreateIntent::getNextAttemptAt,
                                                        System.currentTimeMillis())
                                                .orderByAsc(
                                                        OrdCreateIntent::getNextAttemptAt,
                                                        OrdCreateIntent::getId)
                                                .last("LIMIT " + limit)));
        int advanced = 0;
        for (var hint : pending) {
            var selected =
                    unscoped(
                            () ->
                                    writes.execute(
                                            tx -> {
                                                var op =
                                                        operations.lock(
                                                                "BEAUTY", hint.getOperationNo());
                                                if (op == null) {
                                                    return null;
                                                }
                                                quotes.lock(op.getCurrentQuoteNo());
                                                confirmations.lock(op.getConfirmationNo());
                                                var current = intents.lock(hint.getPayNo());
                                                if (current == null
                                                        || !"COUNTER"
                                                                .equals(current.getCreationKind())
                                                        || OrdCreateIntent.COMMITTED.equals(
                                                                current.getStatus())) {
                                                    return null;
                                                }
                                                if (parentExists(current.getPayNo())) {
                                                    if ("CREATED".equals(op.getPhase())
                                                            && current.getPayNo()
                                                                    .equals(op.getOrderNo())) {
                                                        current.setStatus(
                                                                OrdCreateIntent.COMMITTED);
                                                    } else {
                                                        current.setErrorCode("CONFLICT");
                                                    }
                                                    current.setNextAttemptAt(
                                                            System.currentTimeMillis() + 30_000L);
                                                    changed(intents.updateById(current));
                                                    return null;
                                                }
                                                if (current.getNextAttemptAt()
                                                        > System.currentTimeMillis()) {
                                                    return null;
                                                }
                                                current.setNextAttemptAt(
                                                        System.currentTimeMillis() + 30_000L);
                                                changed(intents.updateById(current));
                                                return current;
                                            }));
            if (selected == null) {
                continue;
            }
            if (OrdCreateIntent.COMPENSATING.equals(selected.getStatus())) {
                if (releaseCompensating(selected)) {
                    advanced++;
                }
                continue;
            }
            // Machine recovery observes only; it never recreates human authorization or dispatches
            // a new order.
            var plan = json.readValue(selected.getCounterPlanJson(), CounterOrderPlan.class);
            if (!selected.getPlanHash().equals(CheckoutDigests.hash(json, plan))) {
                continue;
            }
            var observation =
                    reserveOrObserve(original(plan), selected.getReservationOperationNo(), false);
            try {
                recovery.recordCounterReservation(
                        selected.getPayNo(), selected.getVersion(), observation);
                if (!Objects.equals(selected.getReservationState(), observation.outcome())) {
                    advanced++;
                }
                var compensating =
                        markCompensating(
                                selected.getOperationNo(),
                                ErrorCode.CHECKOUT_QUOTE_STALE.name(),
                                true);
                if (compensating != null && releaseCompensating(compensating)) {
                    advanced++;
                }
            } catch (BizException collision) {
                if (collision.errorCode() != ErrorCode.CONFLICT) {
                    throw collision;
                }
            }
        }
        return advanced;
    }

    private ReservationView reserveOrObserve(
            Original original, String operation, boolean dispatch) {
        try {
            return dispatch
                    ? stock.reserveOriginal(new Reservation(original, operation))
                    : stock.observeReservation(new ReservationLookup(original, operation));
        } catch (RuntimeException lostReply) {
            try {
                return stock.observeReservation(new ReservationLookup(original, operation));
            } catch (RuntimeException unavailable) {
                return new ReservationView(
                        original,
                        operation,
                        "UNKNOWN",
                        List.of(),
                        StockDispositionContract.hash(
                                "STOCK_RESERVATION_V1:UNAVAILABLE:" + operation),
                        clock.now(),
                        "STOCK_OWNER_UNAVAILABLE");
            }
        }
    }

    private OrdCreateIntent markCompensating(String operation, String reason, boolean onlyExpired) {
        return unscoped(
                () ->
                        writes.execute(
                                tx -> {
                                    var op = operations.lock("BEAUTY", operation);
                                    if (op == null || "CREATED".equals(op.getPhase())) {
                                        return null;
                                    }
                                    var quote = quotes.lock(op.getCurrentQuoteNo());
                                    var confirmation =
                                            op.getConfirmationNo() == null
                                                    ? null
                                                    : confirmations.lock(op.getConfirmationNo());
                                    if (quote == null
                                            || confirmation == null
                                            || "CONSUMED".equals(confirmation.getStatus())) {
                                        return null;
                                    }
                                    if (onlyExpired
                                            && clock.now()
                                                    .isBefore(
                                                            quote.getExpiresAt()
                                                                    .toInstant(ZoneOffset.UTC))) {
                                        return null;
                                    }
                                    var hint = recovery.findCounter(operation);
                                    var intent =
                                            hint == null ? null : intents.lock(hint.getPayNo());
                                    if (intent != null) {
                                        // No elapsed reservation lease or empty observation is a
                                        // proof of safe compensation.
                                        if (parentExists(intent.getPayNo())
                                                || OrdCreateIntent.COMMITTED.equals(
                                                        intent.getStatus())
                                                || OrdCreateIntent.RELEASED.equals(
                                                        intent.getStatus())) {
                                            return null;
                                        }
                                        if (OrdCreateIntent.COMPENSATING.equals(
                                                intent.getStatus())) {
                                            return intent;
                                        }
                                        if (!OrdCreateIntent.PREPARED.equals(intent.getStatus())
                                                || !("RESERVED".equals(intent.getReservationState())
                                                        || "REJECTED"
                                                                .equals(
                                                                        intent
                                                                                .getReservationState()))
                                                || intent.getReservationObservationJson() == null) {
                                            return null;
                                        }
                                        var reservation =
                                                json.readValue(
                                                        intent.getReservationObservationJson(),
                                                        ReservationView.class);
                                        var plan =
                                                json.readValue(
                                                        intent.getCounterPlanJson(),
                                                        CounterOrderPlan.class);
                                        if (!intent.getPlanHash()
                                                        .equals(CheckoutDigests.hash(json, plan))
                                                || !original(plan).equals(reservation.original())
                                                || !intent.getReservationOperationNo()
                                                        .equals(
                                                                reservation
                                                                        .reservationOperationNo())
                                                || !intent.getReservationObservationHash()
                                                        .equals(reservation.observationHash())) {
                                            throw BizException.of(ErrorCode.CONFLICT);
                                        }
                                        var evidence =
                                                new CompensationEvidence(
                                                        1, reservation, null, reason);
                                        intent.setStatus(OrdCreateIntent.COMPENSATING);
                                        intent.setReservationObservationJson(
                                                json.writeValueAsString(evidence));
                                        intent.setReservationObservationHash(
                                                CheckoutDigests.hash(json, evidence));
                                        intent.setErrorCode(reason);
                                        intent.setNextAttemptAt(
                                                System.currentTimeMillis() + 30_000L);
                                        changed(intents.updateById(intent));
                                    }
                                    quote.setStatus("REVOKED");
                                    confirmation.setStatus("REVOKED");
                                    op.setPhase("REQUOTE_REQUIRED");
                                    op.setLastErrorCode(reason);
                                    changed(quotes.updateById(quote));
                                    changed(confirmations.updateById(confirmation));
                                    changed(operations.updateById(op));
                                    return intent;
                                }));
    }

    private boolean releaseCompensating(OrdCreateIntent selected) {
        if (!OrdCreateIntent.COMPENSATING.equals(selected.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var evidence =
                json.readValue(
                        selected.getReservationObservationJson(), CompensationEvidence.class);
        var plan = json.readValue(selected.getCounterPlanJson(), CounterOrderPlan.class);
        if (!selected.getPlanHash().equals(CheckoutDigests.hash(json, plan))
                || !selected.getReservationObservationHash()
                        .equals(CheckoutDigests.hash(json, evidence))
                || !original(plan).equals(evidence.reservation().original())
                || !selected.getReservationOperationNo()
                        .equals(evidence.reservation().reservationOperationNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        ReleaseView released;
        try {
            released =
                    stock.releaseOriginal(
                            new ReservationLookup(
                                    original(plan), selected.getReservationOperationNo()));
        } catch (RuntimeException lostReply) {
            released =
                    new ReleaseView(
                            original(plan),
                            selected.getReservationOperationNo(),
                            "UNKNOWN",
                            List.of(),
                            StockDispositionContract.hash(
                                    "STOCK_RESERVATION_RELEASE_V1:UNAVAILABLE:"
                                            + selected.getReservationOperationNo()),
                            clock.now(),
                            "STOCK_OWNER_UNAVAILABLE");
        }
        final ReleaseView observedRelease = released;
        if (!original(plan).equals(observedRelease.original())
                || !selected.getReservationOperationNo()
                        .equals(observedRelease.reservationOperationNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return unscoped(
                () ->
                        writes.execute(
                                tx -> {
                                    var op = operations.lock("BEAUTY", selected.getOperationNo());
                                    if (op == null) {
                                        return false;
                                    }
                                    quotes.lock(op.getCurrentQuoteNo());
                                    confirmations.lock(op.getConfirmationNo());
                                    var current = intents.lock(selected.getPayNo());
                                    if (current == null
                                            || !OrdCreateIntent.COMPENSATING.equals(
                                                    current.getStatus())
                                            || !Objects.equals(
                                                    current.getVersion(), selected.getVersion())) {
                                        return false;
                                    }
                                    if (parentExists(current.getPayNo())) {
                                        current.setErrorCode(ErrorCode.CONFLICT.name());
                                        current.setNextAttemptAt(
                                                System.currentTimeMillis() + 30_000L);
                                        changed(intents.updateById(current));
                                        return false;
                                    }
                                    var updated =
                                            new CompensationEvidence(
                                                    1,
                                                    evidence.reservation(),
                                                    observedRelease,
                                                    evidence.reason());
                                    current.setReservationObservationJson(
                                            json.writeValueAsString(updated));
                                    current.setReservationObservationHash(
                                            CheckoutDigests.hash(json, updated));
                                    current.setNextAttemptAt(System.currentTimeMillis() + 30_000L);
                                    if ("RELEASED".equals(observedRelease.outcome())) {
                                        current.setStatus(OrdCreateIntent.RELEASED);
                                    }
                                    changed(intents.updateById(current));
                                    return "RELEASED".equals(observedRelease.outcome());
                                }));
    }

    private CounterPurchaseAuthorization authorization(
            OrdCheckoutOperation op, PlaceCounterReq request) {
        var quote = quotes.lock(op.getCurrentQuoteNo());
        var confirmation =
                op.getConfirmationNo() == null ? null : confirmations.lock(op.getConfirmationNo());
        if (quote == null
                || confirmation == null
                || !"CONFIRMED".equals(op.getPhase())
                || !"CONFIRMED".equals(quote.getStatus())
                || !"AUTHORIZED".equals(confirmation.getStatus())
                || !request.confirmationNo().equals(confirmation.getConfirmationNo())
                || !"MERCHANT_RECORDED_IN_PERSON".equals(confirmation.getPurchaseAuthority())) {
            throw BizException.of(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        }
        var expected =
                new ExpectedCheckoutQuote(
                        op.getOperationNo(),
                        quote.getQuoteNo(),
                        quote.getRevision(),
                        quote.getQuoteHash(),
                        confirmation.getConfirmationNo(),
                        op.getScopeHash(),
                        confirmation.getExpiresAt().toInstant(ZoneOffset.UTC),
                        "MANUAL_OFFLINE");
        return new CounterPurchaseAuthorization(
                expected,
                party(op),
                confirmation.getAuthorityNamespaceHash(),
                confirmation.getActorNo(),
                confirmation.getActorRealm(),
                confirmation.getActorKind(),
                confirmation.getAccountNo());
    }

    private CounterOrderPlan requirePlan(
            OrdCreateIntent intent,
            OrdCheckoutOperation op,
            CounterPurchaseAuthorization authorization) {
        if (intent == null
                || !"COUNTER".equals(intent.getCreationKind())
                || !op.getOperationNo().equals(intent.getOperationNo())
                || !authorization.expected().confirmationNo().equals(intent.getConfirmationNo())
                || !authorization
                        .authorityNamespaceHash()
                        .equals(intent.getAuthorityNamespaceHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var plan = json.readValue(intent.getCounterPlanJson(), CounterOrderPlan.class);
        if (!intent.getPlanHash().equals(CheckoutDigests.hash(json, plan))
                || !plan.party().equals(party(op))
                || !plan.orderNo().equals(intent.getPayNo())
                || !plan.subOrderNo().equals(intent.getSubOrderNo())
                || !plan.economic().quoteHash().equals(authorization.expected().quoteHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return plan;
    }

    private boolean parentExists(String orderNo) {
        return orders.selectCount(
                        Wrappers.<OrdOrder>lambdaQuery()
                                .eq(OrdOrder::getTenantNo, "MAIN")
                                .eq(OrdOrder::getOrderNo, orderNo))
                > 0;
    }

    private static Original original(CounterOrderPlan plan) {
        return new Original(
                "MAIN",
                plan.economic().entityNo(),
                plan.orderNo(),
                plan.stockAuthority(),
                plan.lifecycleVersion(),
                plan.manifestHash(),
                StockDispositionContract.needs(plan.stockNeeds()));
    }

    private static CustomerParty party(OrdCheckoutOperation op) {
        return new CustomerParty(
                op.getCustomerKind(), op.getBuyerNo(), op.getGuestNo(), op.getIdentityRevision());
    }

    private static void requireScope(OrdCheckoutOperation op, CheckoutScope scope) {
        if (op == null
                || !"MAIN".equals(op.getTenantNo())
                || !scope.entityNo().equals(op.getEntityNo())
                || !scope.storeNo().equals(op.getStoreNo())
                || !"MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority())
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private static void requirePlace(
            OrdCheckoutOperation op, PlaceCounterReq request, String key, String payload) {
        if (!request.confirmationNo().equals(op.getConfirmationNo())) {
            throw BizException.of(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        }
        if (op.getPlaceKeyHash() != null
                && (!key.equals(op.getPlaceKeyHash())
                        || !payload.equals(op.getPlacePayloadHash()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private CheckoutResult result(OrdCheckoutOperation op, boolean pending) {
        return new CheckoutResult(
                new OperationRef(op.getCaller(), op.getOperationNo()),
                pending ? "PLACING" : op.getPhase(),
                op.getVersion(),
                op.getOrderNo(),
                op.getSubOrderNo(),
                op.getSourceObservationHash(),
                op.getLastErrorCode(),
                clock.now());
    }

    private static void changed(int rows) {
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
    }

    private static <T> T unscoped(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action);
    }

    private record Stage(OrdCheckoutOperation operation, CounterOrderPlan plan, boolean dispatch) {}
}
