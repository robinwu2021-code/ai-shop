package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.marketing.CouponPort.MerchantAmount;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt;
import ai.neargo.shop.spi.trade.CheckoutPort.BuyerConfirmation;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutSourceFacts;
import ai.neargo.shop.spi.trade.CheckoutPort.ConfirmCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.DiscountFact;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentFact;
import ai.neargo.shop.spi.trade.CheckoutPort.PlaceCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PromotionOption;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLine;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdCheckoutConfirmation;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdCheckoutQuote;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import ai.neargo.shop.trade.order.service.create.CheckoutCommand;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.order.service.create.CheckoutEconomicSnapshot;
import ai.neargo.shop.trade.order.service.create.CheckoutPricingScope;
import ai.neargo.shop.trade.order.service.create.CheckoutQuoteGuard;
import ai.neargo.shop.trade.order.service.create.ExpectedCheckoutQuote;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;
import ai.neargo.shop.trade.service.CheckoutOrderWriter;
import ai.neargo.shop.trade.service.CheckoutService;
import ai.neargo.shop.trade.service.OrderService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Durable ordinary checkout owner. Identity references identify a buyer; only C can authorize a
 * purchase.
 */
@Service
public class CheckoutServiceImpl implements CheckoutService {
    private final CheckoutMappers.OperationMapper operations;
    private final CheckoutMappers.QuoteMapper quotes;
    private final CheckoutMappers.ConfirmationMapper confirmations;
    private final CheckoutAccessPolicy access;
    private final CustomerIdentityPort identities;
    private final StoreSalesCatalogPort catalogs;
    private final CheckoutOrderWriter writer;
    private final ObjectMapper json;
    private final CheckoutClock clock;
    private final CheckoutQuoteGuard guard;
    private final CreateIntentMapper intents;
    private final CouponPort coupons;
    private final CampaignPort campaigns;
    private final TransactionTemplate writes;
    private final OrderMapper sourceOrders;
    private final SubOrderMapper sourceSubs;
    private final OrderItemMapper sourceItems;
    private final AfterSaleMapper sourceAfterSales;
    private final CheckoutMappers.LineMapper sourceLines;
    private final SettlePort settlement;
    private final TransactionTemplate sourceReads;

    /** Bind durable checkout writes and serialized source reads to the platform transaction manager. */
    public CheckoutServiceImpl(
            CheckoutMappers.OperationMapper operations,
            CheckoutMappers.QuoteMapper quotes,
            CheckoutMappers.ConfirmationMapper confirmations,
            CheckoutAccessPolicy access,
            CustomerIdentityPort identities,
            StoreSalesCatalogPort catalogs,
            CheckoutOrderWriter writer,
            ObjectMapper json,
            CheckoutClock clock,
            CheckoutQuoteGuard guard,
            CreateIntentMapper intents,
            CouponPort coupons,
            CampaignPort campaigns,
            OrderMapper sourceOrders,
            SubOrderMapper sourceSubs,
            OrderItemMapper sourceItems,
            AfterSaleMapper sourceAfterSales,
            CheckoutMappers.LineMapper sourceLines,
            SettlePort settlement,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.operations = operations;
        this.quotes = quotes;
        this.confirmations = confirmations;
        this.access = access;
        this.identities = identities;
        this.catalogs = catalogs;
        this.writer = writer;
        this.json = json;
        this.clock = clock;
        this.guard = guard;
        this.intents = intents;
        this.coupons = coupons;
        this.campaigns = campaigns;
        this.sourceOrders = sourceOrders;
        this.sourceSubs = sourceSubs;
        this.sourceItems = sourceItems;
        this.sourceAfterSales = sourceAfterSales;
        this.sourceLines = sourceLines;
        this.settlement = settlement;
        sourceReads = new TransactionTemplate(manager);
        sourceReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        sourceReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request) {
        var actor = access.requireMerchant(scope, BizPerms.CHECKOUT_PREPARE);
        String keyHash =
                CheckoutDigests.hash(
                        json,
                        List.of(
                                scope.tenantNo(),
                                request.operation().caller(),
                                scope.entityNo(),
                                scope.storeNo(),
                                actor.realm(),
                                actor.subjectKind(),
                                actor.actorNo(),
                                "PREPARE",
                                request.requestKey()));
        String payload =
                CheckoutDigests.hash(
                        json,
                        Arrays.asList(
                                actor,
                                request.operation(),
                                CheckoutDigests.hash(request.customerRef()),
                                request.scopeRevision(),
                                request.scopeHash(),
                                request.lines(),
                                request.payMode(),
                                request.couponNo(),
                                request.activityChoices(),
                                request.remark()));
        var existing =
                unscoped(
                        () ->
                                operations.find(
                                        request.operation().caller(),
                                        request.operation().operationNo()));
        if (existing != null) {
            return replay(existing, scope, actor, keyHash, payload);
        }
        var used =
                unscoped(
                        () ->
                                operations.findPrepareKey(
                                        request.operation().caller(),
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        keyHash));
        if (used != null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        var customer =
                identities.resolveClaimReference(
                        new EntitlementContext(
                                scope.tenantNo(),
                                actor.actorNo(),
                                request.operation().operationNo(),
                                payload),
                        new CustomerIdentityPort.ReferenceQuery(
                                scope.entityNo(), scope.storeNo(), request.customerRef()));
        if (customer == null || customer.userNo() == null || customer.userNo().isBlank()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var catalog = catalogs.read(scope.entityNo(), scope.storeNo(), clock.now());
        var command = command(scope, request);
        var pricingScope =
                new CheckoutPricingScope(
                        scope.tenantNo(),
                        scope.entityNo(),
                        scope.storeNo(),
                        request.scopeRevision(),
                        request.scopeHash(),
                        request.lines(),
                        catalog);
        var economic = writer.quoteFor(customer.userNo(), command, pricingScope);
        var at = clock.now().truncatedTo(ChronoUnit.MILLIS);
        String quoteNo = number("CQ");
        var operation = new OrdCheckoutOperation();
        operation.setOperationNo(request.operation().operationNo());
        operation.setCaller(request.operation().caller());
        operation.setEntityNo(scope.entityNo());
        operation.setStoreNo(scope.storeNo());
        operation.setBuyerNo(customer.userNo());
        operation.setActorNo(actor.actorNo());
        operation.setActorRealm(actor.realm());
        operation.setActorKind(actor.subjectKind());
        operation.setRequestKeyHash(keyHash);
        operation.setPayloadHash(payload);
        operation.setScopeRevision(request.scopeRevision());
        operation.setScopeHash(request.scopeHash());
        operation.setCommandJson(
                json.writeValueAsString(new CheckoutCommand(command, request.lines())));
        operation.setPhase("QUOTED");
        operation.setCurrentQuoteNo(quoteNo);
        operation.setSourceRevision(0L);
        stamp(operation, actor.actorNo(), at);
        var quote = new OrdCheckoutQuote();
        quote.setQuoteNo(quoteNo);
        quote.setOperationNo(operation.getOperationNo());
        quote.setCaller(operation.getCaller());
        quote.setRevision(1L);
        quote.setQuoteHash(economic.quoteHash());
        quote.setPricingJson(json.writeValueAsString(economic));
        quote.setExpiresAt(LocalDateTime.ofInstant(at.plusSeconds(300), ZoneOffset.UTC));
        quote.setStatus("OPEN");
        stamp(quote, actor.actorNo(), at);
        try {
            return unscoped(
                    () ->
                            writes.execute(
                                    status -> {
                                        operations.insert(operation);
                                        quotes.insert(quote);
                                        return view(operation, quote);
                                    }));
        } catch (DuplicateKeyException conflict) {
            var winner =
                    unscoped(
                            () ->
                                    operations.find(
                                            request.operation().caller(),
                                            request.operation().operationNo()));
            if (winner == null) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return replay(winner, scope, actor, keyHash, payload);
        }
    }

    @Override
    public CheckoutQuote readQuote(CheckoutScope scope, OperationRef reference) {
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        return unscoped(
                () -> {
                    var operation = requireOperation(reference);
                    requireScope(operation, scope);
                    return view(operation, requireQuote(operation));
                });
    }

    @Override
    public CheckoutQuote readQuoteForCurrentBuyer(OperationRef reference) {
        String buyer = access.requireCurrentBuyer();
        return unscoped(
                () -> {
                    var operation =
                            operations.findForBuyer(
                                    reference.caller(), reference.operationNo(), buyer);
                    if (operation == null) {
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    }
                    return view(operation, requireQuote(operation));
                });
    }

    @Override
    public BuyerConfirmation confirm(ConfirmCheckoutReq request) {
        String buyer = access.requireCurrentBuyer();
        String key =
                CheckoutDigests.hash(json, List.of("MAIN", buyer, "CONFIRM", request.requestKey()));
        String payload =
                CheckoutDigests.hash(
                        json,
                        List.of(
                                request.operation(),
                                request.quoteNo(),
                                request.quoteRevision(),
                                request.quoteHash()));
        try {
            BuyerConfirmation result =
                    unscoped(
                            () ->
                                    writes.execute(
                                            status -> {
                                                var operation =
                                                        operations.lock(
                                                                request.operation().caller(),
                                                                request.operation().operationNo());
                                                if (operation == null
                                                        || !buyer.equals(operation.getBuyerNo())) {
                                                    throw BizException.of(ErrorCode.NOT_FOUND);
                                                }
                                                var quote =
                                                        quotes.lock(operation.getCurrentQuoteNo());
                                                if (quote == null
                                                        || !request.quoteNo()
                                                                .equals(quote.getQuoteNo())
                                                        || request.quoteRevision()
                                                                != quote.getRevision()
                                                        || !request.quoteHash()
                                                                .equals(quote.getQuoteHash())
                                                        || !operation
                                                                .getOperationNo()
                                                                .equals(quote.getOperationNo())
                                                        || !operation
                                                                .getCaller()
                                                                .equals(quote.getCaller())) {
                                                    throw BizException.of(ErrorCode.CONFLICT);
                                                }
                                                if (operation.getConfirmationNo() != null) {
                                                    var existing =
                                                            confirmations.lock(
                                                                    operation.getConfirmationNo());
                                                    if (existing == null
                                                            || !buyer.equals(existing.getBuyerNo())
                                                            || !key.equals(
                                                                    existing.getRequestKeyHash())
                                                            || !payload.equals(
                                                                    existing.getRequestHash())) {
                                                        throw BizException.of(ErrorCode.CONFLICT);
                                                    }
                                                    return confirmationView(existing);
                                                }
                                                if (!"QUOTED".equals(operation.getPhase())
                                                        || !"OPEN".equals(quote.getStatus())) {
                                                    throw BizException.of(
                                                            ErrorCode.CHECKOUT_QUOTE_STALE);
                                                }
                                                var now =
                                                        clock.now().truncatedTo(ChronoUnit.MILLIS);
                                                if (!now.isBefore(
                                                        quote.getExpiresAt()
                                                                .toInstant(ZoneOffset.UTC))) {
                                                    quote.setStatus("EXPIRED");
                                                    operation.setPhase("REQUOTE_REQUIRED");
                                                    changed(quotes.updateById(quote));
                                                    changed(operations.updateById(operation));
                                                    return null;
                                                }
                                                var money =
                                                        json.readValue(
                                                                quote.getPricingJson(),
                                                                CheckoutEconomicSnapshot.class);
                                                var confirmation = new OrdCheckoutConfirmation();
                                                confirmation.setConfirmationNo(number("CC"));
                                                confirmation.setCaller(operation.getCaller());
                                                confirmation.setOperationNo(
                                                        operation.getOperationNo());
                                                confirmation.setQuoteNo(quote.getQuoteNo());
                                                confirmation.setQuoteRevision(quote.getRevision());
                                                confirmation.setQuoteHash(quote.getQuoteHash());
                                                confirmation.setBuyerNo(buyer);
                                                confirmation.setRequestKeyHash(key);
                                                confirmation.setRequestHash(payload);
                                                confirmation.setAmountMinor(money.dueMinor());
                                                confirmation.setCurrency(money.currency());
                                                confirmation.setPurpose(
                                                        "ORDINARY_SERVICE_PURCHASE");
                                                confirmation.setExpiresAt(quote.getExpiresAt());
                                                confirmation.setStatus("AUTHORIZED");
                                                confirmation.setConfirmedAt(
                                                        LocalDateTime.ofInstant(
                                                                now, ZoneOffset.UTC));
                                                stamp(confirmation, buyer, now);
                                                confirmations.insert(confirmation);
                                                quote.setStatus("CONFIRMED");
                                                changed(quotes.updateById(quote));
                                                operation.setConfirmationNo(
                                                        confirmation.getConfirmationNo());
                                                operation.setPhase("CONFIRMED");
                                                changed(operations.updateById(operation));
                                                return confirmationView(confirmation);
                                            }));
            if (result == null) {
                throw BizException.of(ErrorCode.CHECKOUT_QUOTE_STALE);
            }
            return result;
        } catch (DuplicateKeyException collision) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static BuyerConfirmation confirmationView(OrdCheckoutConfirmation row) {
        return new BuyerConfirmation(
                row.getConfirmationNo(),
                new OperationRef(row.getCaller(), row.getOperationNo()),
                row.getQuoteNo(),
                row.getQuoteRevision(),
                row.getQuoteHash(),
                row.getExpiresAt().toInstant(ZoneOffset.UTC),
                row.getStatus());
    }

    private static void changed(int affected) {
        if (affected != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public CheckoutResult place(PlaceCheckoutReq request) {
        String buyer = access.requireCurrentBuyer();
        String key =
                CheckoutDigests.hash(json, List.of("MAIN", buyer, "PLACE", request.requestKey()));
        String payload =
                CheckoutDigests.hash(json, List.of(request.operation(), request.confirmationNo()));
        var observed =
                unscoped(
                        () ->
                                operations.findForBuyer(
                                        request.operation().caller(),
                                        request.operation().operationNo(),
                                        buyer));
        if (observed == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        requirePlaceReplay(observed, request, key, payload);
        if ("CREATED".equals(observed.getPhase())) {
            return result(observed);
        }
        var quote = unscoped(() -> requireQuote(observed));
        var pricing = json.readValue(quote.getPricingJson(), CheckoutEconomicSnapshot.class);
        var frozen = json.readValue(observed.getCommandJson(), CheckoutCommand.class);
        // Preparation only; final local owner locks and actual writer pricing remain authoritative.
        catalogs.read(observed.getEntityNo(), observed.getStoreNo(), clock.now());
        campaigns.requireOrdinarySelection(buyer, pricing.activity());
        coupons.quoteOrdinary(
                buyer,
                frozen.command().couponNo(),
                List.of(
                        new MerchantAmount(
                                observed.getEntityNo(),
                                pricing.goodsMinor() - pricing.activity().total(),
                                observed.getStoreNo())));
        try {
            unscoped(
                    () ->
                            writes.execute(
                                    status -> {
                                        var op =
                                                operations.lock(
                                                        request.operation().caller(),
                                                        request.operation().operationNo());
                                        if (op == null || !buyer.equals(op.getBuyerNo())) {
                                            throw BizException.of(ErrorCode.NOT_FOUND);
                                        }
                                        requirePlaceReplay(op, request, key, payload);
                                        if ("CREATED".equals(op.getPhase())) {
                                            return op;
                                        }
                                        lockedAuthorization(op, request);
                                        if (op.getPlaceKeyHash() == null) {
                                            op.setPlaceKeyHash(key);
                                            op.setPlacePayloadHash(payload);
                                            changed(operations.updateById(op));
                                        }
                                        return op;
                                    }));
        } catch (DuplicateKeyException collision) {
            throw BizException.of(ErrorCode.CONFLICT);
        } catch (BizException failure) {
            if (failure.errorCode() == ErrorCode.CHECKOUT_QUOTE_STALE) {
                unscoped(
                        () ->
                                writes.execute(
                                        status -> {
                                            var op =
                                                    operations.lock(
                                                            request.operation().caller(),
                                                            request.operation().operationNo());
                                            if (op != null
                                                    && buyer.equals(op.getBuyerNo())
                                                    && "CONFIRMED".equals(op.getPhase())
                                                    && request.confirmationNo()
                                                            .equals(op.getConfirmationNo())
                                                    && (op.getPlaceKeyHash() == null
                                                            || key.equals(op.getPlaceKeyHash()))) {
                                                var q = quotes.lock(op.getCurrentQuoteNo());
                                                var c = confirmations.lock(op.getConfirmationNo());
                                                op.setPhase("REQUOTE_REQUIRED");
                                                op.setLastErrorCode(failure.errorCode().name());
                                                q.setStatus("EXPIRED");
                                                c.setStatus("REVOKED");
                                                changed(confirmations.updateById(c));
                                                changed(quotes.updateById(q));
                                                changed(operations.updateById(op));
                                            }
                                            return null;
                                        }));
            }
            throw failure;
        }
        try {
            return unscoped(
                    () ->
                            writes.execute(
                                    status -> {
                                        var op =
                                                operations.lock(
                                                        request.operation().caller(),
                                                        request.operation().operationNo());
                                        if (op == null || !buyer.equals(op.getBuyerNo())) {
                                            throw BizException.of(ErrorCode.NOT_FOUND);
                                        }
                                        requirePlaceReplay(op, request, key, payload);
                                        if ("CREATED".equals(op.getPhase())) {
                                            return result(op);
                                        }
                                        var authorized = lockedAuthorization(op, request);
                                        var intent = originalIntent(op);
                                        if (intent != null) {
                                            if (OrdCreateIntent.RELEASED.equals(
                                                    intent.getStatus())) {
                                                rejectReleased(op, authorized);
                                                return result(op);
                                            }
                                            return new CheckoutResult(
                                                    request.operation(),
                                                    "PLACING",
                                                    op.getVersion(),
                                                    null,
                                                    null,
                                                    op.getSourceObservationHash(),
                                                    intent.getErrorCode(),
                                                    clock.now());
                                        }
                                        var expected =
                                                new ExpectedCheckoutQuote(
                                                        op.getOperationNo(),
                                                        authorized.quote().getQuoteNo(),
                                                        authorized.quote().getRevision(),
                                                        authorized.quote().getQuoteHash(),
                                                        authorized
                                                                .confirmation()
                                                                .getConfirmationNo(),
                                                        op.getScopeHash(),
                                                        authorized
                                                                .confirmation()
                                                                .getExpiresAt()
                                                                .toInstant(ZoneOffset.UTC));
                                        var current =
                                                json.readValue(
                                                        op.getCommandJson(), CheckoutCommand.class);
                                        var created =
                                                writer.createConfirmedFor(
                                                        buyer,
                                                        current.command(),
                                                        internalKey(op),
                                                        expected);
                                        if (created.subOrders() == null
                                                || created.subOrders().size() != 1) {
                                            throw BizException.of(ErrorCode.CONFLICT);
                                        }
                                        guard.complete(
                                                expected,
                                                created.payOrderNo(),
                                                created.subOrders().get(0).orderNo());
                                        return result(
                                                operations.find(
                                                        op.getCaller(), op.getOperationNo()));
                                    }));
        } catch (BizException failure) {
            if (failure.errorCode() == ErrorCode.CHECKOUT_QUOTE_STALE
                    || failure.errorCode() == ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION) {
                unscoped(
                        () ->
                                writes.execute(
                                        status -> {
                                            var op =
                                                    operations.lock(
                                                            request.operation().caller(),
                                                            request.operation().operationNo());
                                            if (op != null
                                                    && "CONFIRMED".equals(op.getPhase())
                                                    && key.equals(op.getPlaceKeyHash())) {
                                                var q = quotes.lock(op.getCurrentQuoteNo());
                                                var c = confirmations.lock(op.getConfirmationNo());
                                                op.setPhase("REQUOTE_REQUIRED");
                                                op.setLastErrorCode(failure.errorCode().name());
                                                q.setStatus("REVOKED");
                                                c.setStatus("REVOKED");
                                                changed(confirmations.updateById(c));
                                                changed(quotes.updateById(q));
                                                changed(operations.updateById(op));
                                            }
                                            return null;
                                        }));
            }
            throw failure;
        }
    }

    @Override
    public CheckoutResult findOperation(CheckoutScope scope, OperationRef reference) {
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        return unscoped(
                () -> {
                    var op = requireOperation(reference);
                    requireScope(op, scope);
                    if ("CREATED".equals(op.getPhase())) {
                        return result(op);
                    }
                    var intent = originalIntent(op);
                    if (intent != null && !OrdCreateIntent.RELEASED.equals(intent.getStatus())) {
                        return new CheckoutResult(
                                reference,
                                "PLACING",
                                op.getVersion(),
                                null,
                                null,
                                op.getSourceObservationHash(),
                                intent.getErrorCode(),
                                clock.now());
                    }
                    if (intent != null) {
                        return new CheckoutResult(
                                reference,
                                "REJECTED",
                                op.getVersion(),
                                null,
                                null,
                                op.getSourceObservationHash(),
                                "CREATE_INTENT_RELEASED",
                                clock.now());
                    }
                    return result(op);
                });
    }

    private void requirePlaceReplay(
            OrdCheckoutOperation op, PlaceCheckoutReq request, String key, String payload) {
        if (!Objects.equals(request.confirmationNo(), op.getConfirmationNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (op.getPlaceKeyHash() != null
                && (!key.equals(op.getPlaceKeyHash())
                        || !payload.equals(op.getPlacePayloadHash()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private Authorized lockedAuthorization(OrdCheckoutOperation op, PlaceCheckoutReq request) {
        var q = quotes.lock(op.getCurrentQuoteNo());
        var c = confirmations.lock(request.confirmationNo());
        if (!"CONFIRMED".equals(op.getPhase())
                || q == null
                || c == null
                || !"CONFIRMED".equals(q.getStatus())
                || !"AUTHORIZED".equals(c.getStatus())
                || !op.getOperationNo().equals(q.getOperationNo())
                || !op.getOperationNo().equals(c.getOperationNo())
                || !op.getBuyerNo().equals(c.getBuyerNo())
                || !q.getQuoteNo().equals(c.getQuoteNo())
                || !q.getQuoteHash().equals(c.getQuoteHash())
                || !q.getRevision().equals(c.getQuoteRevision())) {
            throw BizException.of(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        }
        if (!clock.now().isBefore(q.getExpiresAt().toInstant(ZoneOffset.UTC))
                || !clock.now().isBefore(c.getExpiresAt().toInstant(ZoneOffset.UTC))) {
            throw BizException.of(ErrorCode.CHECKOUT_QUOTE_STALE);
        }
        return new Authorized(q, c);
    }

    private void rejectReleased(OrdCheckoutOperation op, Authorized authorized) {
        op.setPhase("REJECTED");
        op.setLastErrorCode("CREATE_INTENT_RELEASED");
        authorized.quote().setStatus("REVOKED");
        authorized.confirmation().setStatus("REVOKED");
        changed(confirmations.updateById(authorized.confirmation()));
        changed(quotes.updateById(authorized.quote()));
        changed(operations.updateById(op));
    }

    private OrdCreateIntent originalIntent(OrdCheckoutOperation op) {
        return intents.selectOne(
                Wrappers.<OrdCreateIntent>lambdaQuery()
                        .eq(OrdCreateIntent::getTenantNo, "MAIN")
                        .eq(OrdCreateIntent::getEndpoint, "POST /mp/order")
                        .eq(OrdCreateIntent::getIdemKey, internalKey(op)));
    }

    private String internalKey(OrdCheckoutOperation op) {
        return "CHK_"
                + CheckoutDigests.hash(json, List.of("MAIN", op.getCaller(), op.getOperationNo()));
    }

    private CheckoutResult result(OrdCheckoutOperation op) {
        return new CheckoutResult(
                new OperationRef(op.getCaller(), op.getOperationNo()),
                op.getPhase(),
                op.getVersion(),
                op.getOrderNo(),
                op.getSubOrderNo(),
                op.getSourceObservationHash(),
                op.getLastErrorCode(),
                clock.now());
    }

    private record Authorized(OrdCheckoutQuote quote, OrdCheckoutConfirmation confirmation) {}

    @Override
    public CheckoutSourceFacts source(CheckoutScope scope, SourceLookup request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        return unscoped(
                () -> {
                    CheckoutSourceFacts last = null;
                    for (int attempt = 0; attempt < 2; attempt++) {
                        var observed = requireOperation(request.operation());
                        requireSourceReference(observed, scope, request);
                        var order = sourceOrder(observed.getOrderNo());
                        PaymentReceipt receipt = null;
                        if (order != null
                                && present(order.getPayChannel())
                                && present(order.getPayTradeNo())) {
                            try {
                                receipt =
                                        settlement.successPayment(
                                                order.getOrderNo(),
                                                order.getPayChannel(),
                                                order.getPayTradeNo());
                            } catch (RuntimeException unavailable) {
                                /* An unavailable funds owner cannot prove zero receipt. */
                            }
                        }
                        var payment = receipt;
                        var read =
                                sourceReads.execute(
                                        status -> sourceSnapshot(scope, request, order, payment));
                        last = read.facts();
                        if (!read.retry()) {
                            return last;
                        }
                    }
                    return last;
                });
    }

    private SourceRead sourceSnapshot(
            CheckoutScope scope, SourceLookup request, OrdOrder observed, PaymentReceipt receipt) {
        // This must be the first SQL in the new RR transaction. All business facts below are
        // ordinary consistent reads; no sub/item current read may be mixed into this snapshot.
        var op = operations.lock(request.operation().caller(), request.operation().operationNo());
        if (op == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        requireSourceReference(op, scope, request);
        var order = sourceOrder(op.getOrderNo());
        var sub =
                sourceSubs.selectOne(
                        Wrappers.<OrdSubOrder>lambdaQuery()
                                .eq(OrdSubOrder::getTenantNo, "MAIN")
                                .eq(OrdSubOrder::getSubOrderNo, op.getSubOrderNo()));
        var items =
                sourceItems.selectList(
                        Wrappers.<OrdItem>lambdaQuery()
                                .eq(OrdItem::getTenantNo, "MAIN")
                                .eq(OrdItem::getSubOrderNo, op.getSubOrderNo())
                                .orderByAsc(OrdItem::getId));
        var mappings =
                sourceLines.selectList(
                        Wrappers.<OrdCheckoutLine>lambdaQuery()
                                .eq(OrdCheckoutLine::getTenantNo, "MAIN")
                                .eq(OrdCheckoutLine::getCaller, op.getCaller())
                                .eq(OrdCheckoutLine::getOperationNo, op.getOperationNo())
                                .orderByAsc(OrdCheckoutLine::getOrderItemId));
        var afterSales =
                sourceAfterSales.selectList(
                        Wrappers.<OrdAfterSale>lambdaQuery()
                                .eq(OrdAfterSale::getTenantNo, "MAIN")
                                .eq(OrdAfterSale::getSubOrderNo, op.getSubOrderNo())
                                .orderByAsc(OrdAfterSale::getAfterSaleNo));
        var refundWork = sourceLines.refundWork(op.getSubOrderNo());
        boolean changedAttempt =
                order != null
                        && (observed == null
                                || !Objects.equals(order.getPayChannel(), observed.getPayChannel())
                                || !Objects.equals(
                                        order.getPayTradeNo(), observed.getPayTradeNo()));
        if (order == null
                || sub == null
                || !op.getOrderNo().equals(sub.getOrderNo())
                || !op.getBuyerNo().equals(order.getUserNo())
                || !op.getBuyerNo().equals(sub.getUserNo())
                || !op.getEntityNo().equals(sub.getEntityNo())
                || !op.getStoreNo().equals(sub.getStoreNo())
                || !op.getOperationNo().equals(sub.getSourceOperationNo())
                || !OrdSubOrder.SERVICE_EXECUTION.equals(sub.getFulfillmentAuthority())
                || !"STORE_VERIFY".equals(sub.getFulfillment())
                || order.getPayAmount() == null
                || order.getPayAmount() < 0
                || !Objects.equals(order.getPayAmount(), sub.getPayAmount())
                || !"CNY".equals(order.getCurrency())
                || items.isEmpty()
                || items.size() != mappings.size()
                || receipt == null
                || !present(receipt.paymentNo())
                || !"SUCCESS".equals(receipt.status())
                || !op.getOrderNo().equals(receipt.orderNo())
                || !op.getBuyerNo().equals(receipt.userNo())
                || !Objects.equals(order.getPayChannel(), receipt.payChannel())
                || !Objects.equals(order.getPayTradeNo(), receipt.tradeNo())
                || !"CNY".equals(receipt.currency())
                || receipt.amountMinor() != order.getPayAmount()) {
            return new SourceRead(unknownSource(op, order), changedAttempt);
        }
        long refunded = sub.getRefundedMinor() == null ? 0 : sub.getRefundedMinor();
        if (refunded < 0 || refunded > receipt.amountMinor()) {
            return new SourceRead(unknownSource(op, order), false);
        }
        boolean closed =
                "CANCELLED".equals(order.getStatus())
                        || "CLOSED".equals(order.getStatus())
                        || "CANCELLED".equals(sub.getStatus())
                        || "REFUNDED".equals(sub.getStatus());
        boolean blocked =
                closed
                        || !"PAID".equals(order.getStatus())
                        || !Set.of("WAIT_FULFILL", "FULFILLING").contains(sub.getStatus())
                        || afterSales.stream()
                                .anyMatch(
                                        a ->
                                                !Set.of("CLOSED", "REJECTED", "REFUNDED")
                                                        .contains(a.getStatus()))
                        || refundWork.stream()
                                .anyMatch(
                                        w ->
                                                "POINTS".equals(w.kind())
                                                        ? !Set.of("COMMITTED", "ABORTED")
                                                                .contains(w.status())
                                                        : !"SUCCEEDED".equals(w.status())
                                                                || !"DONE"
                                                                        .equals(
                                                                                w
                                                                                        .resourceStatus()));
        var lines = new ArrayList<SourceLine>();
        var itemFacts = new ArrayList<Object>();
        long totalPaid = 0;
        try {
            for (int index = 0; index < items.size(); index++) {
                var item = items.get(index);
                var mapping = mappings.get(index);
                int refundedQty = item.getRefundedQty() == null ? 0 : item.getRefundedQty();
                long itemRefund = item.getRefundedMinor() == null ? 0 : item.getRefundedMinor();
                if (!item.getId().equals(mapping.getOrderItemId())
                        || !op.getOrderNo().equals(item.getOrderNo())
                        || !op.getSubOrderNo().equals(mapping.getSubOrderNo())
                        || !Objects.equals(item.getGoodsNo(), mapping.getGoodsNo())
                        || !Objects.equals(item.getSkuNo(), mapping.getSkuNo())
                        || !Objects.equals(item.getQty(), mapping.getQuantity())
                        || !"COUNT".equals(mapping.getUom())
                        || item.getQty() == null
                        || item.getQty() < 1
                        || refundedQty < 0
                        || refundedQty > item.getQty()
                        || item.getPaidShareMinor() == null
                        || item.getPaidShareMinor() < 0
                        || itemRefund < 0
                        || itemRefund > item.getPaidShareMinor()) {
                    return new SourceRead(unknownSource(op, order), false);
                }
                totalPaid = Math.addExact(totalPaid, item.getPaidShareMinor());
                lines.add(
                        new SourceLine(
                                mapping.getSourceLineNo(),
                                mapping.getClientLineRef(),
                                item.getGoodsNo(),
                                item.getSkuNo(),
                                item.getQty(),
                                closed ? 0 : item.getQty() - refundedQty,
                                "COUNT",
                                item.getPaidShareMinor()));
                itemFacts.add(
                        Arrays.asList(
                                item.getId(),
                                item.getPrice(),
                                item.getAmount(),
                                item.getPaidShareMinor(),
                                item.getQty(),
                                refundedQty,
                                itemRefund));
            }
        } catch (ArithmeticException overflow) {
            return new SourceRead(unknownSource(op, order), false);
        }
        if (totalPaid != order.getPayAmount()) {
            return new SourceRead(unknownSource(op, order), false);
        }
        long received = receipt.amountMinor() - refunded;
        long outstanding = Math.max(0, (closed ? 0 : order.getPayAmount() - refunded) - received);
        String financial =
                refunded == 0
                        ? "PAID"
                        : refunded == receipt.amountMinor() ? "REFUNDED" : "PARTIALLY_REFUNDED";
        String execution = blocked ? "BLOCKED" : "NOT_STARTED";
        String hash =
                CheckoutDigests.hash(
                        json,
                        Arrays.asList(
                                "CHECKOUT_SOURCE_V1",
                                op.getOrderNo(),
                                op.getSubOrderNo(),
                                op.getBuyerNo(),
                                op.getEntityNo(),
                                op.getStoreNo(),
                                order.getStatus(),
                                order.getCurrency(),
                                order.getPayAmount(),
                                order.getPayChannel(),
                                order.getPayTradeNo(),
                                sub.getStatus(),
                                sub.getRefundedMinor(),
                                sub.getRefundProgress(),
                                itemFacts,
                                lines,
                                receipt,
                                afterSales.stream()
                                        .map(
                                                a ->
                                                        Arrays.asList(
                                                                a.getAfterSaleNo(),
                                                                a.getStatus(),
                                                                a.getRefundMinor(),
                                                                a.getApprovedMinor(),
                                                                a.getRefundedAt(),
                                                                a.getRefundPaymentNo()))
                                        .toList(),
                                refundWork,
                                financial,
                                execution));
        if (!hash.equals(op.getSourceObservationHash())) {
            op.setSourceRevision(Math.addExact(op.getSourceRevision(), 1L));
            op.setSourceObservationHash(hash);
            changed(operations.updateById(op));
        }
        var payment =
                new PaymentFact(
                        null,
                        receipt.paymentNo(),
                        null,
                        receipt.payChannel(),
                        receipt.tradeNo(),
                        receipt.status(),
                        receipt.amountMinor(),
                        receipt.currency(),
                        null,
                        clock.now());
        return new SourceRead(
                new CheckoutSourceFacts(
                        request.operation(),
                        op.getOrderNo(),
                        op.getSubOrderNo(),
                        op.getBuyerNo(),
                        op.getEntityNo(),
                        op.getStoreNo(),
                        op.getSourceRevision(),
                        hash,
                        sub.getStatus(),
                        "CNY",
                        order.getPayAmount(),
                        received,
                        outstanding,
                        financial,
                        lines,
                        List.of(payment),
                        execution,
                        clock.now()),
                false);
    }

    private OrdOrder sourceOrder(String no) {
        return sourceOrders.selectOne(
                Wrappers.<OrdOrder>lambdaQuery()
                        .eq(OrdOrder::getTenantNo, "MAIN")
                        .eq(OrdOrder::getOrderNo, no));
    }

    private void requireSourceReference(
            OrdCheckoutOperation op, CheckoutScope scope, SourceLookup request) {
        requireScope(op, scope);
        if (!"CREATED".equals(op.getPhase())
                || op.getOrderNo() == null
                || op.getSubOrderNo() == null
                || request.subOrderNo() != null
                        && !request.subOrderNo().equals(op.getSubOrderNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private CheckoutSourceFacts unknownSource(OrdCheckoutOperation op, OrdOrder order) {
        long due =
                order == null || order.getPayAmount() == null
                        ? 0
                        : Math.max(0, order.getPayAmount());
        return new CheckoutSourceFacts(
                new OperationRef(op.getCaller(), op.getOperationNo()),
                op.getOrderNo(),
                op.getSubOrderNo(),
                op.getBuyerNo(),
                op.getEntityNo(),
                op.getStoreNo(),
                op.getSourceRevision(),
                op.getSourceObservationHash(),
                order == null ? null : order.getStatus(),
                "CNY",
                due,
                null,
                null,
                "UNKNOWN",
                List.of(),
                List.of(),
                "BLOCKED",
                clock.now());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record SourceRead(CheckoutSourceFacts facts, boolean retry) {}

    private CheckoutQuote replay(
            OrdCheckoutOperation operation,
            CheckoutScope scope,
            CheckoutAccessPolicy.MerchantActor actor,
            String key,
            String payload) {
        requireScope(operation, scope);
        if (!Objects.equals(actor.actorNo(), operation.getActorNo())
                || !Objects.equals(actor.realm(), operation.getActorRealm())
                || !Objects.equals(actor.subjectKind(), operation.getActorKind())
                || !key.equals(operation.getRequestKeyHash())
                || !payload.equals(operation.getPayloadHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return unscoped(() -> view(operation, requireQuote(operation)));
    }

    private OrdCheckoutOperation requireOperation(OperationRef reference) {
        var operation = operations.find(reference.caller(), reference.operationNo());
        if (operation == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return operation;
    }

    private OrdCheckoutQuote requireQuote(OrdCheckoutOperation operation) {
        var quote = quotes.find(operation.getCurrentQuoteNo());
        if (quote == null
                || !operation.getCaller().equals(quote.getCaller())
                || !operation.getOperationNo().equals(quote.getOperationNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return quote;
    }

    private void requireScope(OrdCheckoutOperation operation, CheckoutScope scope) {
        if (!scope.tenantNo().equals(operation.getTenantNo())
                || !scope.entityNo().equals(operation.getEntityNo())
                || !scope.storeNo().equals(operation.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private CheckoutQuote view(OrdCheckoutOperation operation, OrdCheckoutQuote quote) {
        var money = json.readValue(quote.getPricingJson(), CheckoutEconomicSnapshot.class);
        var promotions =
                money.activity().applied().stream()
                        .map(
                                a ->
                                        new PromotionOption(
                                                a.activityNo(), "ACTIVITY", a.name(), true, null))
                        .toList();
        var discounts = new ArrayList<DiscountFact>();
        money.activity()
                .applied()
                .forEach(
                        a ->
                                discounts.add(
                                        new DiscountFact(
                                                a.activityNo(),
                                                "ACTIVITY",
                                                a.amountMinor(),
                                                a.platformMinor() == 0
                                                        ? "MERCHANT"
                                                        : a.platformMinor() == a.amountMinor()
                                                                ? "PLATFORM"
                                                                : "MIXED",
                                                null)));
        var frozen = json.readValue(operation.getCommandJson(), CheckoutCommand.class);
        if (frozen.command().couponNo() != null) {
            discounts.add(
                    new DiscountFact(
                            frozen.command().couponNo(),
                            "COUPON",
                            money.coupon().totalDiscount(),
                            money.coupon().byMerchant() ? "MERCHANT" : "PLATFORM",
                            null));
        }
        return new CheckoutQuote(
                new OperationRef(operation.getCaller(), operation.getOperationNo()),
                quote.getQuoteNo(),
                quote.getRevision(),
                quote.getQuoteHash(),
                quote.getExpiresAt().toInstant(ZoneOffset.UTC),
                operation.getBuyerNo(),
                operation.getEntityNo(),
                operation.getStoreNo(),
                money.currency(),
                money.lines(),
                money.goodsMinor(),
                money.discountMinor(),
                money.dueMinor(),
                money.payMode(),
                money.fulfillment(),
                money.fulfillmentAuthority(),
                promotions,
                discounts,
                operation.getPhase(),
                operation.getVersion());
    }

    private static OrderService.CreateOrderCommand command(
            CheckoutScope scope, PrepareCheckoutReq request) {
        return new OrderService.CreateOrderCommand(
                request.lines().stream()
                        .map(
                                l ->
                                        new OrderService.CreateOrderCommand.Item(
                                                null, l.skuNo(), l.quantity()))
                        .toList(),
                "STORE_VERIFY",
                null,
                null,
                request.couponNo(),
                0L,
                request.remark(),
                null,
                request.payMode(),
                null,
                null,
                null,
                false,
                new TreeMap<>(request.activityChoices()),
                Map.of(scope.entityNo(), scope.storeNo()),
                Map.of());
    }

    private static String number(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static void stamp(BaseEntity row, String actor, Instant at) {
        row.setTenantNo("MAIN");
        row.setVersion(1L);
        row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        row.setUpdatedAt(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
    }

    private static <T> T unscoped(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action);
    }
}
