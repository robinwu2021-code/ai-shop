package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.AcceptCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterConfirmation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.BoundActionContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockDispositionPort.CurrentState;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.Outcome;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionEvents.ManualServiceAdmissionChanged;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionEvents.ManualServiceReceiptRecorded;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualAdmissionView;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceLine;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdCheckoutQuote;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmission;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmissionAction;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.order.service.create.CheckoutEconomicSnapshot;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy.CashierActor;
import ai.neargo.shop.trade.service.CheckoutService;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Durable merchant declaration, common order transition and separately observed original stock
 * admission.
 */
@Service
public class ManualServiceAdmissionServiceImpl implements ManualServiceAdmissionService {
    private final CheckoutService checkout;
    private final CheckoutAccessPolicy access;
    private final MerchantOperatorAccessPort merchants;
    private final CheckoutMappers.OperationMapper operations;
    private final CheckoutMappers.QuoteMapper quotes;
    private final CheckoutMappers.LineMapper mappings;
    private final OrderMapper orders;
    private final SubOrderMapper children;
    private final OrderItemMapper items;
    private final CreateIntentMapper intents;
    private final ManualAdmissionMapper declarations;
    private final ManualAdmissionMapper.ActionMapper actions;
    private final OrderStatusLogMapper logs;
    private final StockDispositionPort stock;
    private final ObjectMapper json;
    private final CheckoutClock clock;
    private final OutboxEventBus events;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public ManualServiceAdmissionServiceImpl(
            CheckoutService checkout,
            CheckoutAccessPolicy access,
            MerchantOperatorAccessPort merchants,
            CheckoutMappers.OperationMapper operations,
            CheckoutMappers.QuoteMapper quotes,
            CheckoutMappers.LineMapper mappings,
            OrderMapper orders,
            SubOrderMapper children,
            OrderItemMapper items,
            CreateIntentMapper intents,
            ManualAdmissionMapper declarations,
            ManualAdmissionMapper.ActionMapper actions,
            OrderStatusLogMapper logs,
            StockDispositionPort stock,
            ObjectMapper json,
            CheckoutClock clock,
            OutboxEventBus events,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.checkout = checkout;
        this.access = access;
        this.merchants = merchants;
        this.operations = operations;
        this.quotes = quotes;
        this.mappings = mappings;
        this.orders = orders;
        this.children = children;
        this.items = items;
        this.intents = intents;
        this.declarations = declarations;
        this.actions = actions;
        this.logs = logs;
        this.stock = stock;
        this.json = json;
        this.clock = clock;
        this.events = events;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public CounterCheckoutQuote prepareCounter(CheckoutScope scope, PrepareCounterReq request) {
        return checkout.prepareCounter(scope, request);
    }

    @Override
    public CounterCheckoutQuote readCounterQuote(CheckoutScope scope, OperationRef operation) {
        return checkout.readCounterQuote(scope, operation);
    }

    @Override
    public CounterConfirmation acceptCounter(CheckoutScope scope, AcceptCounterReq request) {
        return checkout.acceptCounter(scope, request);
    }

    @Override
    public CheckoutResult placeCounter(CheckoutScope scope, PlaceCounterReq request) {
        return checkout.placeCounter(scope, request);
    }

    @Override
    public CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request) {
        outside();
        return checkout.prepareManual(scope, request);
    }

    @Override
    public ManualAdmissionView confirmForOrder(
            CheckoutScope scope, String orderNo, ConfirmManualReceipt request) {
        outside();
        cashier(scope, request);
        requireOrder(scope, orderNo, request.operation());
        return confirm(scope, request);
    }

    @Override
    public ManualAdmissionView queryForOrder(
            CheckoutScope scope, String orderNo, ManualLookup request) {
        outside();
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        requireOrder(scope, orderNo, request.operation());
        return query(scope, request);
    }

    @Override
    public ManualSourceFacts sourceForOrder(
            CheckoutScope scope, String orderNo, SourceLookup request) {
        outside();
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        requireOrder(scope, orderNo, request.operation());
        return source(scope, request);
    }

    private void requireOrder(CheckoutScope scope, String no, OperationRef ref) {
        var original = unscoped(() -> operation(scope, ref));
        if (no == null || !no.equals(original.getOrderNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    @Override
    public ManualAdmissionView confirm(CheckoutScope scope, ConfirmManualReceipt request) {
        outside();
        var actor = cashier(scope, request);
        var hint = unscoped(() -> operation(scope, request.operation()));
        if (hint.getOrderNo() == null) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        requireCleanSource(hint.getOrderNo());
        String key = key(scope, actor, request.operation(), request.requestKey());
        String payload =
                CheckoutDigests.hash(
                        json,
                        Arrays.asList(
                                "MANUAL_RECEIPT_V1",
                                actor,
                                request.operation(),
                                hint.getOrderNo(),
                                request.expectedOrderRevision(),
                                request.method(),
                                request.declaredMinor(),
                                request.currency(),
                                request.receivedAt(),
                                request.note()));
        if (request.receivedAt().isAfter(clock.now())
                || request.note() != null
                        && (request.note().contains("@")
                                || request.note().matches("(?s).*\\d{9,}.*"))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!actor.equals(cashier(scope, request))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var declaration =
                unscoped(
                        () ->
                                writes.execute(
                                        tx -> {
                                            var parent = declarations.lockParent(hint.getOrderNo());
                                            var subs = declarations.lockChildren(hint.getOrderNo());
                                            var op =
                                                    operations.lock(
                                                            request.operation().caller(),
                                                            request.operation().operationNo());
                                            requireScope(op, scope);
                                            if (parent == null
                                                    || subs.size() != 1
                                                    || !Objects.equals(
                                                            op.getOrderNo(), parent.getOrderNo())) {
                                                throw conflict();
                                            }
                                            requireCleanSource(parent.getOrderNo());
                                            var previous =
                                                    declarations.lockByOrder(
                                                            scope.entityNo(),
                                                            scope.storeNo(),
                                                            parent.getOrderNo());
                                            if (previous != null) {
                                                if (!key.equals(previous.getRequestKeyHash())
                                                        || !payload.equals(
                                                                previous.getPayloadHash())) {
                                                    throw conflict();
                                                }
                                                requireDeclarationParty(op, previous);
                                                return previous;
                                            }
                                            var facts = facts(op, parent, subs.getFirst());
                                            if (!"WAIT_OFFLINE_PAY".equals(parent.getStatus())
                                                    || !"WAIT_PAY".equals(facts.child().getStatus())
                                                    || parent.getVersion()
                                                            != request.expectedOrderRevision()
                                                    || !Objects.equals(
                                                            parent.getPayAmount(),
                                                            request.declaredMinor())
                                                    || !request.currency()
                                                            .equals(parent.getCurrency())) {
                                                throw BizException.of(
                                                        ErrorCode.ORDER_STATE_ILLEGAL);
                                            }
                                            var original = original(facts);
                                            var row = new OrdManualServiceAdmission();
                                            row.setDeclarationNo(number("MD"));
                                            row.setOperationNo(op.getOperationNo());
                                            row.setCaller(op.getCaller());
                                            row.setOrderNo(parent.getOrderNo());
                                            row.setSubOrderNo(facts.child().getSubOrderNo());
                                            row.setEntityNo(scope.entityNo());
                                            row.setStoreNo(scope.storeNo());
                                            row.setBuyerNo(op.getBuyerNo());
                                            row.setCustomerKind(op.getCustomerKind());
                                            row.setGuestNo(op.getGuestNo());
                                            row.setIdentityRevision(op.getIdentityRevision());
                                            row.setAdmissionKind("MANUAL_OFFLINE");
                                            row.setActorNo(actor.actorNo());
                                            row.setActorRealm(actor.realm());
                                            row.setActorKind(actor.subjectKind());
                                            row.setAccountNo(actor.accountNo());
                                            row.setRequestKeyHash(key);
                                            row.setPayloadHash(payload);
                                            row.setMethod(request.method().name());
                                            row.setDeclaredMinor(request.declaredMinor());
                                            row.setCurrency(request.currency());
                                            row.setReceivedAt(utc(request.receivedAt()));
                                            row.setRecordedAt(utc(clock.now()));
                                            row.setNote(request.note());
                                            row.setReceiptProvenance("MANUAL_DECLARATION");
                                            row.setPhase("STOCK_PENDING");
                                            row.setAdmissionRevision(1L);
                                            row.setOrderRevision(parent.getVersion());
                                            row.setQuoteHash(facts.quote().getQuoteHash());
                                            row.setStockAuthority(original.authority());
                                            row.setStockLifecycleVersion(
                                                    original.lifecycleVersion());
                                            row.setStockManifestJson(
                                                    json.writeValueAsString(original));
                                            row.setStockManifestHash(original.manifestHash());
                                            row.setStockOperationNo(
                                                    StockDispositionContract.operationNo(original));
                                            row.setNextRetryAt(utc(clock.now()));
                                            stamp(row, actor.actorNo());
                                            changed(declarations.insert(row));
                                            changed(
                                                    declarations.confirmParent(
                                                            parent.getOrderNo(),
                                                            parent.getVersion(),
                                                            row.getDeclarationNo(),
                                                            payload,
                                                            request.receivedAt().toEpochMilli(),
                                                            clock.now().toEpochMilli(),
                                                            actor.actorNo()));
                                            changed(
                                                    declarations.confirmChild(
                                                            parent.getOrderNo(),
                                                            facts.child().getSubOrderNo(),
                                                            facts.child().getVersion(),
                                                            op.getOperationNo(),
                                                            row.getDeclarationNo(),
                                                            payload));
                                            row.setOrderRevision(
                                                    Math.addExact(parent.getVersion(), 1));
                                            changed(declarations.updateById(row));
                                            action(row, "RECEIPT_RECORDED");
                                            status(
                                                    parent.getOrderNo(),
                                                    "PAYMENT",
                                                    "WAIT_OFFLINE_PAY",
                                                    "PAID",
                                                    actor.actorNo());
                                            status(
                                                    facts.child().getSubOrderNo(),
                                                    "ORDER",
                                                    "WAIT_PAY",
                                                    "FULFILLING",
                                                    actor.actorNo());
                                            events.publish(
                                                    new ManualServiceReceiptRecorded(
                                                            row.getOrderNo(),
                                                            row.getDeclarationNo(),
                                                            row.getAdmissionRevision()));
                                            return row;
                                        }));
        progress(declaration, true);
        requireCleanSource(declaration.getOrderNo());
        if (!actor.equals(cashier(scope, request))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return unscoped(
                () ->
                        view(
                                operation(scope, request.operation()),
                                declarations.snapshot(
                                        request.operation().caller(),
                                        request.operation().operationNo(),
                                        scope.entityNo(),
                                        scope.storeNo())));
    }

    @Override
    public ManualAdmissionView query(CheckoutScope scope, ManualLookup request) {
        outside();
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        var op = unscoped(() -> operation(scope, request.operation()));
        requireCleanSource(op.getOrderNo());
        var row =
                unscoped(
                        () ->
                                declarations.snapshot(
                                        op.getCaller(),
                                        op.getOperationNo(),
                                        scope.entityNo(),
                                        scope.storeNo()));
        if (row != null) {
            if (request.declarationNo() != null
                    && !request.declarationNo().equals(row.getDeclarationNo())) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            if (request.requestKey() != null) {
                var actor = access.requireMerchant(scope, BizPerms.ORDER_VIEW);
                var account =
                        merchants
                                .resolveAuthorizedOperator(
                                        new MerchantOperatorAccessPort.Query(
                                                scope.tenantNo(),
                                                scope.entityNo(),
                                                scope.storeNo(),
                                                actor.actorNo(),
                                                actor.realm(),
                                                actor.subjectKind(),
                                                BizPerms.ORDER_VIEW))
                                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
                String expected =
                        CheckoutDigests.hash(
                                json,
                                List.of(
                                        "MANUAL_RECEIPT_KEY_V1",
                                        scope.tenantNo(),
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        actor.realm(),
                                        actor.subjectKind(),
                                        actor.actorNo(),
                                        account.accountNo(),
                                        op.getOrderNo(),
                                        op.getOperationNo(),
                                        request.requestKey()));
                if (!expected.equals(row.getRequestKeyHash())) {
                    throw BizException.of(ErrorCode.NOT_FOUND);
                }
            }
            progress(row, true);
        }
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        requireCleanSource(op.getOrderNo());
        return unscoped(
                () ->
                        view(
                                operation(scope, request.operation()),
                                declarations.snapshot(
                                        op.getCaller(),
                                        op.getOperationNo(),
                                        scope.entityNo(),
                                        scope.storeNo())));
    }

    @Override
    public ManualSourceFacts source(CheckoutScope scope, SourceLookup request) {
        outside();
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        var op = unscoped(() -> operation(scope, request.operation()));
        requireCleanSource(op.getOrderNo());
        var row =
                unscoped(
                        () ->
                                declarations.snapshot(
                                        op.getCaller(),
                                        op.getOperationNo(),
                                        scope.entityNo(),
                                        scope.storeNo()));
        if (row != null) {
            progress(row, true);
        }
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        return unscoped(
                () ->
                        reads.execute(
                                tx -> {
                                    // The operation root is the first SQL in this fresh RR
                                    // observation transaction.
                                    var current =
                                            operations.lock(
                                                    request.operation().caller(),
                                                    request.operation().operationNo());
                                    requireScope(current, scope);
                                    requireCleanSource(current.getOrderNo());
                                    if (request.subOrderNo() != null
                                            && !request.subOrderNo()
                                                    .equals(current.getSubOrderNo())) {
                                        throw conflict();
                                    }
                                    var declaration =
                                            declarations.snapshot(
                                                    current.getCaller(),
                                                    current.getOperationNo(),
                                                    scope.entityNo(),
                                                    scope.storeNo());
                                    var parent = parent(current.getOrderNo());
                                    if (parent == null) {
                                        return sourceView(
                                                current,
                                                null,
                                                null,
                                                "AWAITING_RECEIPT",
                                                List.of(),
                                                false,
                                                null);
                                    }
                                    var child = child(current.getSubOrderNo());
                                    if (declaration == null) {
                                        return sourceView(
                                                current,
                                                parent,
                                                null,
                                                "AWAITING_RECEIPT",
                                                List.of(),
                                                true,
                                                null);
                                    }
                                    try {
                                        requireDeclarationParty(current, declaration);
                                        var facts = facts(current, parent, child);
                                        String state = admissionState(declaration);
                                        if (!Objects.equals(
                                                        parent.getVersion(),
                                                        declaration.getOrderRevision())
                                                || !"PAID".equals(parent.getStatus())
                                                || !"FULFILLING".equals(child.getStatus())) {
                                            state = "UNKNOWN";
                                        }
                                        var sourceLines = new ArrayList<ManualSourceLine>();
                                        if ("ADMITTED".equals(state)) {
                                            for (var line : facts.lines()) {
                                                sourceLines.add(
                                                        new ManualSourceLine(
                                                                line.getSourceLineNo(),
                                                                line.getClientLineRef(),
                                                                line.getGoodsNo(),
                                                                line.getSkuNo(),
                                                                line.getQuantity(),
                                                                line.getQuantity(),
                                                                line.getUom()));
                                            }
                                        }
                                        return sourceView(
                                                current,
                                                parent,
                                                declaration,
                                                state,
                                                sourceLines,
                                                !"UNKNOWN".equals(state),
                                                facts);
                                    } catch (BizException invalid) {
                                        return sourceView(
                                                current,
                                                parent,
                                                declaration,
                                                "UNKNOWN",
                                                List.of(),
                                                false,
                                                null);
                                    }
                                }));
    }

    @Override
    public int recoverPending(int limit) {
        outside();
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("recovery limit");
        }
        var pending = unscoped(() -> declarations.pending(limit));
        int changed = 0;
        for (var row : pending) {
            if (progress(row, true)) {
                changed++;
            }
        }
        return changed;
    }

    private boolean progress(OrdManualServiceAdmission observed, boolean dispatch) {
        if ("SUSPENDED".equals(observed.getPhase()) || "CANCELLED".equals(observed.getPhase())) {
            return false;
        }
        if (contaminated(observed.getOrderNo())) {
            return unscoped(
                    () ->
                            writes.execute(
                                    tx -> {
                                        var current = lockOriginal(observed);
                                        return current != null
                                                && contaminated(current.getOrderNo())
                                                && suspendModeConflict(current);
                                    }));
        }
        var operation =
                unscoped(() -> operations.find(observed.getCaller(), observed.getOperationNo()));
        if (operation == null) {
            return false;
        }
        requireDeclarationParty(operation, observed);
        Original original = json.readValue(observed.getStockManifestJson(), Original.class);
        Disposition disposition =
                stock.observeOriginal(
                        new StockDispositionPort.Lookup(original, observed.getStockOperationNo()));
        if (dispatch && disposition.outcome() == Outcome.PENDING) {
            stock.confirmOriginal(
                    new StockDispositionPort.Confirmation(
                            original, observed.getStockOperationNo()));
            disposition =
                    stock.observeOriginal(
                            new StockDispositionPort.Lookup(
                                    original, observed.getStockOperationNo()));
        }
        final Disposition actual = disposition;
        boolean applied = applied(original, observed.getStockOperationNo(), actual);
        String phase =
                applied
                        ? "ADMITTED"
                        : (actual.outcome() == Outcome.REJECTED
                                        || actual.outcome() == Outcome.APPLIED)
                                ? "SUSPENDED"
                                : "STOCK_PENDING";
        return unscoped(
                () ->
                        writes.execute(
                                tx -> {
                                    var current = lockOriginal(observed);
                                    if (current == null) {
                                        return false;
                                    }
                                    if (contaminated(current.getOrderNo())) {
                                        return suspendModeConflict(current);
                                    }
                                    if (phase.equals(current.getPhase())
                                            && Objects.equals(
                                                    current.getStockObservationHash(),
                                                    actual.observationHash())) {
                                        if ("STOCK_PENDING".equals(phase)) {
                                            long expected = current.getVersion();
                                            current.setNextRetryAt(
                                                    utc(clock.now().plusSeconds(30)));
                                            current.setUpdatedAt(utc(clock.now()));
                                            changed(
                                                    declarations.advanceAdmission(
                                                            current, expected));
                                        }
                                        return false;
                                    }
                                    long expected = current.getVersion();
                                    current.setPhase(phase);
                                    current.setStockOutcome(actual.outcome().name());
                                    current.setStockState(actual.state().name());
                                    current.setStockObservationHash(actual.observationHash());
                                    current.setReasonCode(actual.reasonCode());
                                    current.setAdmissionRevision(
                                            Math.addExact(current.getAdmissionRevision(), 1));
                                    current.setUpdatedAt(utc(clock.now()));
                                    current.setNextRetryAt(
                                            "STOCK_PENDING".equals(phase)
                                                    ? utc(clock.now().plusSeconds(30))
                                                    : null);
                                    changed(declarations.advanceAdmission(current, expected));
                                    action(current, "ADMISSION_CHANGED");
                                    events.publish(
                                            new ManualServiceAdmissionChanged(
                                                    current.getOrderNo(),
                                                    current.getDeclarationNo(),
                                                    current.getAdmissionRevision(),
                                                    admissionState(current)));
                                    return true;
                                }));
    }

    private OrdManualServiceAdmission lockOriginal(OrdManualServiceAdmission observed) {
        var parent = declarations.lockParent(observed.getOrderNo());
        var subs = declarations.lockChildren(observed.getOrderNo());
        var op = operations.lock(observed.getCaller(), observed.getOperationNo());
        var current =
                declarations.lockByOrder(
                        observed.getEntityNo(), observed.getStoreNo(), observed.getOrderNo());
        if (parent == null
                || subs.size() != 1
                || op == null
                || current == null
                || !"MANUAL_OFFLINE".equals(parent.getAdmissionKind())
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())
                || !"MANUAL_OFFLINE".equals(subs.getFirst().getAdmissionKind())
                || !Objects.equals(op.getOrderNo(), parent.getOrderNo())
                || !Objects.equals(subs.getFirst().getSubOrderNo(), current.getSubOrderNo())
                || !current.getDeclarationNo().equals(observed.getDeclarationNo())
                || !current.getPayloadHash().equals(observed.getPayloadHash())
                || !Objects.equals(current.getOrderRevision(), parent.getVersion())
                || !Objects.equals(current.getVersion(), observed.getVersion())
                || "SUSPENDED".equals(current.getPhase())
                || "CANCELLED".equals(current.getPhase())) {
            return null;
        }
        return current;
    }

    private boolean suspendModeConflict(OrdManualServiceAdmission current) {
        long expected = current.getVersion();
        current.setPhase("SUSPENDED");
        current.setReasonCode("SOURCE_MODE_CONFLICT");
        current.setAdmissionRevision(Math.addExact(current.getAdmissionRevision(), 1));
        current.setUpdatedAt(utc(clock.now()));
        current.setNextRetryAt(null);
        // Keep the last genuine stock observation exactly; contamination is no stock outcome.
        changed(declarations.advanceAdmission(current, expected));
        action(current, "ADMISSION_CHANGED");
        events.publish(
                new ManualServiceAdmissionChanged(
                        current.getOrderNo(),
                        current.getDeclarationNo(),
                        current.getAdmissionRevision(),
                        "SUSPENDED"));
        return true;
    }

    private boolean contaminated(String orderNo) {
        return orderNo != null && unscoped(() -> declarations.financialConflicts(orderNo)) != 0;
    }

    private void requireCleanSource(String orderNo) {
        if (contaminated(orderNo)) {
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
    }

    private boolean applied(Original original, String operation, Disposition disposition) {
        if (disposition.outcome() != Outcome.APPLIED
                || !original.equals(disposition.original())
                || !operation.equals(disposition.operationNo())
                || !List.of(CurrentState.ALLOCATED, CurrentState.CONSUMED)
                        .contains(disposition.state())
                || disposition.lines().isEmpty()
                || disposition.observationHash() == null) {
            return false;
        }
        var ids = new HashSet<String>();
        var needs = new ArrayList<StockPort.SkuQty>();
        for (var row : disposition.lines()) {
            if (!ids.add(row.ownerRowNo())
                    || row.originalQuantity() <= 0
                    || row.releasedQuantity() != 0
                    || row.revision() < 0
                    || row.disposition() != disposition.state()
                    || row.allocatedQuantity() + row.consumedQuantity() != row.originalQuantity()
                    || disposition.state() == CurrentState.ALLOCATED
                            && row.allocatedQuantity() != row.originalQuantity()
                    || disposition.state() == CurrentState.CONSUMED
                            && row.consumedQuantity() != row.originalQuantity()) {
                return false;
            }
            needs.add(
                    new StockPort.SkuQty(
                            row.skuNo(),
                            row.originalQuantity(),
                            row.sourceStoreNo(),
                            row.fulfillmentRef(),
                            original.lifecycleVersion()));
        }
        return original.manifestHash()
                .equals(
                        StockDispositionContract.manifestHash(
                                StockDispositionContract.needs(needs)));
    }

    private static CustomerParty party(OrdCheckoutOperation op) {
        return new CustomerParty(
                op.getCustomerKind(), op.getBuyerNo(), op.getGuestNo(), op.getIdentityRevision());
    }

    private void requireDeclarationParty(
            OrdCheckoutOperation op, OrdManualServiceAdmission declaration) {
        if (!party(op)
                        .equals(
                                new CustomerParty(
                                        declaration.getCustomerKind(),
                                        declaration.getBuyerNo(),
                                        declaration.getGuestNo(),
                                        declaration.getIdentityRevision()))
                || !op.getCaller().equals(declaration.getCaller())
                || !op.getOperationNo().equals(declaration.getOperationNo())
                || !Objects.equals(op.getOrderNo(), declaration.getOrderNo())
                || !Objects.equals(op.getSubOrderNo(), declaration.getSubOrderNo())
                || !op.getEntityNo().equals(declaration.getEntityNo())
                || !op.getStoreNo().equals(declaration.getStoreNo())) {
            throw conflict();
        }
    }

    private Facts facts(OrdCheckoutOperation op, OrdOrder parent, OrdSubOrder child) {
        if (parent == null
                || child == null
                || !"CREATED".equals(op.getPhase())
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())
                || !"MANUAL_OFFLINE".equals(parent.getAdmissionKind())
                || !"MANUAL_OFFLINE".equals(child.getAdmissionKind())
                || !Objects.equals(parent.getOrderNo(), child.getOrderNo())
                || !party(op)
                        .equals(
                                new CustomerParty(
                                        parent.getCustomerKind(),
                                        parent.getUserNo(),
                                        parent.getGuestNo(),
                                        parent.getIdentityRevision()))
                || !party(op)
                        .equals(
                                new CustomerParty(
                                        child.getCustomerKind(),
                                        child.getUserNo(),
                                        child.getGuestNo(),
                                        child.getIdentityRevision()))
                || "MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority())
                        && (!op.getEntityNo().equals(parent.getEntityNo())
                                || !op.getStoreNo().equals(parent.getStoreNo())
                                || !op.getCaller().equals(parent.getCaller())
                                || !op.getOperationNo().equals(parent.getOperationNo())
                                || !op.getCaller().equals(child.getCaller()))
                || !op.getEntityNo().equals(child.getEntityNo())
                || !op.getStoreNo().equals(child.getStoreNo())
                || !op.getOperationNo().equals(child.getSourceOperationNo())
                || !OrdSubOrder.SERVICE_EXECUTION.equals(child.getFulfillmentAuthority())
                || !"STORE_VERIFY".equals(child.getFulfillment())
                || parent.getPayChannel() != null
                || parent.getPayTradeNo() != null
                || Boolean.TRUE.equals(parent.getPointsIntegrated())
                || child.getGroupNo() != null
                || child.getPeriodNo() != null
                || nz(child.getPointsDeductMinor()) != 0
                || nz(child.getDiscountPlatform()) != 0
                || nz(child.getDiscountMerchant()) != 0
                || declarations.afterSaleConflicts(child.getSubOrderNo()) != 0
                || declarations.financialConflicts(parent.getOrderNo()) != 0
                || !mappings.refundWork(child.getSubOrderNo()).isEmpty()) {
            throw conflict();
        }
        var quote = quotes.find(op.getCurrentQuoteNo());
        if (quote == null || !"CONSUMED".equals(quote.getStatus())) {
            throw conflict();
        }
        var economics = json.readValue(quote.getPricingJson(), CheckoutEconomicSnapshot.class);
        if (!Objects.equals(op.getBuyerNo(), economics.buyerNo())
                || !op.getEntityNo().equals(economics.entityNo())
                || !op.getStoreNo().equals(economics.storeNo())) {
            throw conflict();
        }
        var sourceLines =
                mappings.selectList(
                        Wrappers.<OrdCheckoutLine>lambdaQuery()
                                .eq(OrdCheckoutLine::getTenantNo, "MAIN")
                                .eq(OrdCheckoutLine::getCaller, op.getCaller())
                                .eq(OrdCheckoutLine::getOperationNo, op.getOperationNo())
                                .orderByAsc(OrdCheckoutLine::getOrderItemId));
        var originalItems =
                items.selectList(
                        Wrappers.<OrdItem>lambdaQuery()
                                .eq(OrdItem::getTenantNo, "MAIN")
                                .eq(OrdItem::getSubOrderNo, child.getSubOrderNo())
                                .orderByAsc(OrdItem::getId));
        if (sourceLines.isEmpty()
                || sourceLines.size() != originalItems.size()
                || economics.lines().size() != sourceLines.size()
                || economics.dueMinor() <= 0
                || !Objects.equals(parent.getPayAmount(), economics.dueMinor())
                || !Objects.equals(child.getPayAmount(), economics.dueMinor())) {
            throw conflict();
        }
        for (var line : sourceLines) {
            var item =
                    originalItems.stream()
                            .filter(value -> value.getId().equals(line.getOrderItemId()))
                            .findFirst()
                            .orElseThrow(this::conflict);
            var priced =
                    economics.lines().stream()
                            .filter(value -> value.clientLineRef().equals(line.getClientLineRef()))
                            .findFirst()
                            .orElseThrow(this::conflict);
            if (!child.getSubOrderNo().equals(line.getSubOrderNo())
                    || !"COUNT".equals(line.getUom())
                    || !line.getSkuNo().equals(item.getSkuNo())
                    || !line.getGoodsNo().equals(item.getGoodsNo())
                    || !Objects.equals(line.getQuantity(), item.getQty())
                    || line.getQuantity() != priced.quantity()
                    || !line.getSkuNo().equals(priced.skuNo())
                    || !Objects.equals(item.getPrice(), priced.unitPriceMinor())
                    || !Objects.equals(item.getAmount(), priced.grossMinor())
                    || !Objects.equals(item.getPaidShareMinor(), priced.dueMinor())
                    || Boolean.TRUE.equals(item.getIsGift())
                    || nz(item.getRefundedMinor()) != 0
                    || nz(item.getRefundedQty()) != 0) {
                throw conflict();
            }
        }
        return new Facts(op, parent, child, quote, sourceLines, originalItems);
    }

    private Original original(Facts facts) {
        var intent = intents.lock(facts.parent().getOrderNo());
        if (intent == null || !"COMMITTED".equals(intent.getStatus())) {
            throw conflict();
        }
        List<StockPort.SkuQty> stored =
                json.readValue(intent.getStockNeedsJson(), new TypeReference<>() {});
        if (stored.isEmpty()
                || stored.stream()
                        .anyMatch(
                                need ->
                                        !Objects.equals(
                                                        need.lifecycleVersion(),
                                                        facts.child()
                                                                .getInventoryLifecycleVersion())
                                                || !facts.child()
                                                        .getSubOrderNo()
                                                        .equals(need.fulfillmentRef())
                                                || !facts.op()
                                                        .getStoreNo()
                                                        .equals(need.storeNo()))) {
            throw conflict();
        }
        var allocated =
                StockDispositionContract.needs(
                        facts.lines().stream()
                                .map(
                                        line ->
                                                new StockPort.SkuQty(
                                                        line.getSkuNo(),
                                                        line.getQuantity(),
                                                        facts.child().getStoreNo(),
                                                        facts.child().getSubOrderNo(),
                                                        facts.child()
                                                                .getInventoryLifecycleVersion()))
                                .toList());
        var actual = StockDispositionContract.needs(stored);
        if (!allocated.equals(actual)
                || !party(facts.op())
                        .equals(
                                new CustomerParty(
                                        intent.getCustomerKind(),
                                        intent.getUserNo(),
                                        intent.getGuestNo(),
                                        intent.getIdentityRevision()))
                || "MERCHANT_RECORDED_IN_PERSON".equals(facts.op().getPurchaseAuthority())
                        && (!"COUNTER".equals(intent.getCreationKind())
                                || !facts.op().getOperationNo().equals(intent.getOperationNo())
                                || !facts.op()
                                        .getConfirmationNo()
                                        .equals(intent.getConfirmationNo())
                                || !facts.child().getSubOrderNo().equals(intent.getSubOrderNo()))) {
            throw conflict();
        }
        return new Original(
                "MAIN",
                facts.op().getEntityNo(),
                facts.parent().getOrderNo(),
                intent.getStockAuthority(),
                facts.child().getInventoryLifecycleVersion(),
                StockDispositionContract.manifestHash(actual),
                actual);
    }

    private ManualSourceFacts sourceView(
            OrdCheckoutOperation op,
            OrdOrder parent,
            OrdManualServiceAdmission declaration,
            String state,
            List<ManualSourceLine> lines,
            boolean verified,
            Facts snapshot) {
        long revision =
                op.getManualObservationRevision() == null ? 0 : op.getManualObservationRevision();
        String hash = op.getManualObservationHash();
        if (verified) {
            var child = snapshot == null ? child(op.getSubOrderNo()) : snapshot.child();
            var sourceRows =
                    snapshot == null
                            ? mappings.selectList(
                                    Wrappers.<OrdCheckoutLine>lambdaQuery()
                                            .eq(OrdCheckoutLine::getTenantNo, "MAIN")
                                            .eq(OrdCheckoutLine::getCaller, op.getCaller())
                                            .eq(
                                                    OrdCheckoutLine::getOperationNo,
                                                    op.getOperationNo()))
                            : snapshot.lines();
            var originalItems =
                    snapshot == null
                            ? items.selectList(
                                    Wrappers.<OrdItem>lambdaQuery()
                                            .eq(OrdItem::getTenantNo, "MAIN")
                                            .eq(OrdItem::getSubOrderNo, op.getSubOrderNo()))
                            : snapshot.items();
            var mappingVersions =
                    sourceRows.stream()
                            .sorted(
                                    Comparator.comparing(OrdCheckoutLine::getSourceLineNo)
                                            .thenComparing(OrdCheckoutLine::getId))
                            .map(
                                    line ->
                                            new MappingRevision(
                                                    line.getSourceLineNo(),
                                                    line.getId(),
                                                    line.getVersion()))
                            .toList();
            var itemVersions =
                    originalItems.stream()
                            .sorted(Comparator.comparing(OrdItem::getId))
                            .map(item -> new ItemRevision(item.getId(), item.getVersion()))
                            .toList();
            String observed =
                    CheckoutDigests.hash(
                            json,
                            Arrays.asList(
                                    "MANUAL_SOURCE_V1",
                                    op.getOperationNo(),
                                    op.getBuyerNo(),
                                    op.getOrderNo(),
                                    op.getSubOrderNo(),
                                    parent == null ? 0 : parent.getVersion(),
                                    child == null ? 0 : child.getVersion(),
                                    declaration == null ? 0 : declaration.getAdmissionRevision(),
                                    mappingVersions,
                                    itemVersions,
                                    state,
                                    lines));
            if ("MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority())) {
                observed =
                        CheckoutDigests.hash(
                                json,
                                Arrays.asList(
                                        "MANUAL_COUNTER_SOURCE_V1",
                                        party(op),
                                        op.getConfirmationNo(),
                                        op.getAuthorityNamespaceHash(),
                                        observed));
            }
            if (!Objects.equals(hash, observed)) {
                op.setManualObservationHash(observed);
                op.setManualObservationRevision(Math.addExact(revision, 1));
                changed(operations.updateById(op));
                revision = op.getManualObservationRevision();
                hash = observed;
            }
        }
        return new ManualSourceFacts(
                new OperationRef(op.getCaller(), op.getOperationNo()),
                op.getOrderNo(),
                op.getSubOrderNo(),
                party(op),
                op.getEntityNo(),
                op.getStoreNo(),
                "MANUAL_OFFLINE",
                revision,
                hash,
                parent == null ? 0 : parent.getVersion(),
                declaration == null ? 0 : declaration.getAdmissionRevision(),
                state,
                lines,
                clock.now());
    }

    private ManualAdmissionView view(
            OrdCheckoutOperation op, OrdManualServiceAdmission declaration) {
        if (declaration != null) {
            requireDeclarationParty(op, declaration);
        }
        var parent = parent(op.getOrderNo());
        if (declaration == null) {
            return new ManualAdmissionView(
                    new OperationRef(op.getCaller(), op.getOperationNo()),
                    op.getOrderNo(),
                    op.getSubOrderNo(),
                    parent == null ? 0 : parent.getVersion(),
                    0,
                    "AWAITING_RECEIPT",
                    null,
                    null,
                    null,
                    null,
                    "CNY",
                    null,
                    null,
                    "AWAITING_RECEIPT",
                    null);
        }
        return new ManualAdmissionView(
                new OperationRef(op.getCaller(), op.getOperationNo()),
                op.getOrderNo(),
                op.getSubOrderNo(),
                parent == null ? 0 : parent.getVersion(),
                declaration.getAdmissionRevision(),
                admissionState(declaration),
                "MANUAL_DECLARATION",
                declaration.getDeclarationNo(),
                ManualMethod.valueOf(declaration.getMethod()),
                declaration.getDeclaredMinor(),
                declaration.getCurrency(),
                declaration.getReceivedAt().toInstant(ZoneOffset.UTC),
                declaration.getRecordedAt().toInstant(ZoneOffset.UTC),
                declaration.getPhase(),
                declaration.getReasonCode());
    }

    private String admissionState(OrdManualServiceAdmission row) {
        return switch (row.getPhase()) {
            case "ADMITTED" -> "ADMITTED";
            case "SUSPENDED" -> "SUSPENDED";
            case "CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private void action(OrdManualServiceAdmission declaration, String kind) {
        var action = new OrdManualServiceAdmissionAction();
        action.setActionNo(number("MA"));
        action.setDeclarationNo(declaration.getDeclarationNo());
        action.setOrderNo(declaration.getOrderNo());
        action.setEntityNo(declaration.getEntityNo());
        action.setStoreNo(declaration.getStoreNo());
        action.setAdmissionRevision(declaration.getAdmissionRevision());
        action.setAction(kind);
        action.setPayloadHash(declaration.getPayloadHash());
        action.setResultJson(
                json.writeValueAsString(
                        Map.of(
                                "phase",
                                declaration.getPhase(),
                                "revision",
                                declaration.getAdmissionRevision())));
        action.setReasonCode(declaration.getReasonCode());
        stamp(action, declaration.getActorNo());
        changed(actions.insert(action));
    }

    private void status(String no, String aggregate, String from, String to, String actor) {
        var row = new OrdStatusLog();
        row.setTenantNo("MAIN");
        row.setSubOrderNo(no);
        row.setAggregateType(aggregate);
        row.setAggregateNo(no);
        row.setEvent("MANUAL_RECEIPT");
        row.setFromStatus(from);
        row.setStatus(to);
        row.setLabel("人工确认服务收款");
        row.setOperatorType(OrdStatusLog.BY_MERCHANT);
        row.setOperatorNo(actor);
        row.setAt(clock.now().toEpochMilli());
        row.setCreatedAt(utc(clock.now()));
        changed(logs.insert(row));
    }

    private CashierActor cashier(CheckoutScope scope, ConfirmManualReceipt request) {
        CashierActor actor;
        if (BoundActionContext.present()) {
            var identity = DelegatedIdentityContext.current();
            BoundActionContext.require(
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    ManualServiceAdmissionInternalApi.ENDPOINT,
                                    "CONFIRM",
                                    identity,
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    request.operation().operationNo()),
                            new Scoped<>(scope, request)));
            var merchant = access.requireMerchant(scope, BizPerms.CASH_RECEIVE);
            var account =
                    merchants
                            .resolveAuthorizedOperator(
                                    new MerchantOperatorAccessPort.Query(
                                            scope.tenantNo(),
                                            scope.entityNo(),
                                            scope.storeNo(),
                                            merchant.actorNo(),
                                            merchant.realm(),
                                            merchant.subjectKind(),
                                            BizPerms.CASH_RECEIVE))
                            .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
            actor =
                    new CashierActor(
                            merchant.tenantNo(),
                            merchant.entityNo(),
                            merchant.storeNo(),
                            merchant.actorNo(),
                            merchant.realm(),
                            merchant.subjectKind(),
                            account.accountNo());
        } else {
            actor = access.requireCurrentCashier(scope);
        }
        access.requireMerchant(scope, BizPerms.MANUAL_CONFIRM);
        var account =
                merchants
                        .resolveAuthorizedOperator(
                                new MerchantOperatorAccessPort.Query(
                                        scope.tenantNo(),
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        actor.actorNo(),
                                        actor.realm(),
                                        actor.subjectKind(),
                                        BizPerms.MANUAL_CONFIRM))
                        .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        if (!actor.accountNo().equals(account.accountNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return actor;
    }

    private String key(
            CheckoutScope scope, CashierActor actor, OperationRef operation, String key) {
        var original = unscoped(() -> operation(scope, operation));
        return CheckoutDigests.hash(
                json,
                List.of(
                        "MANUAL_RECEIPT_KEY_V1",
                        scope.tenantNo(),
                        scope.entityNo(),
                        scope.storeNo(),
                        actor.realm(),
                        actor.subjectKind(),
                        actor.actorNo(),
                        actor.accountNo(),
                        original.getOrderNo(),
                        operation.operationNo(),
                        key));
    }

    private OrdCheckoutOperation operation(CheckoutScope scope, OperationRef reference) {
        var op = operations.find(reference.caller(), reference.operationNo());
        requireScope(op, scope);
        return op;
    }

    private void requireScope(OrdCheckoutOperation op, CheckoutScope scope) {
        if (op == null
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())
                || !scope.entityNo().equals(op.getEntityNo())
                || !scope.storeNo().equals(op.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private OrdOrder parent(String no) {
        return no == null
                ? null
                : orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, no));
    }

    private OrdSubOrder child(String no) {
        return children.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, no));
    }

    private void stamp(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setVersion(1L);
        row.setDeleted(0);
        row.setCreatedAt(utc(clock.now()));
        row.setUpdatedAt(utc(clock.now()));
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static String number(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static void changed(int count) {
        if (count != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> T unscoped(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(work);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
    }

    private record Facts(
            OrdCheckoutOperation op,
            OrdOrder parent,
            OrdSubOrder child,
            OrdCheckoutQuote quote,
            List<OrdCheckoutLine> lines,
            List<OrdItem> items) {}

    private record MappingRevision(String sourceLineNo, Long id, Long revision) {}

    private record ItemRevision(Long id, Long revision) {}
}
