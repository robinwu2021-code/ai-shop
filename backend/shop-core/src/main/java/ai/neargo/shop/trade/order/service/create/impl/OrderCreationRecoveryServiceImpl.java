package ai.neargo.shop.trade.order.service.create.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockDispositionContract.ReservationOwner;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationView;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlan;
import ai.neargo.shop.trade.order.service.create.CounterPurchaseAuthorization;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Stock is never reserved before its durable claim; recovery sends run after releasing the intent row lock. */
@Service
public class OrderCreationRecoveryServiceImpl implements OrderCreationRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(OrderCreationRecoveryServiceImpl.class);
    private static final String ENDPOINT = "POST /mp/order";
    private static final long LEASE_MS = 120_000L;
    private final CreateIntentMapper intents;
    private final PaymentMapper payments;
    private final StockPort stock;
    private final ObjectMapper json;
    private final TransactionTemplate independent;
    private final String authority;
    private static final String COUNTER_ENDPOINT = "POST /biz/service-orders/counter-orders";
    @Autowired private CheckoutMappers.OperationMapper counterOperations;
    @Autowired private CheckoutMappers.QuoteMapper counterQuotes;
    @Autowired private CheckoutMappers.ConfirmationMapper counterConfirmations;

    @Override
    public OrdCreateIntent findCounter(String operationNo) {
        return DataScopeContext.executeWithoutScope(
                () ->
                        intents.selectOne(
                                Wrappers.<OrdCreateIntent>lambdaQuery()
                                        .eq(OrdCreateIntent::getTenantNo, "MAIN")
                                        .eq(OrdCreateIntent::getCreationKind, "COUNTER")
                                        .eq(OrdCreateIntent::getCaller, "BEAUTY")
                                        .eq(OrdCreateIntent::getOperationNo, operationNo)));
    }

    @Override
    public CounterOrderPlan registerCounter(
            CounterOrderPlan plan, CounterPurchaseAuthorization authorization) {
        if (plan == null
                || authorization == null
                || !plan.party().equals(authorization.party())
                || !plan.operationNo().equals(authorization.expected().operationNo())
                || !plan.economic().quoteHash().equals(authorization.expected().quoteHash())
                || plan.intentRevision() != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        String encoded = json.writeValueAsString(plan);
        String hash = CheckoutDigests.hash(json, plan);
        return DataScopeContext.executeWithoutScope(
                () ->
                        independent.execute(
                                tx -> {
                                    var row = new OrdCreateIntent();
                                    row.setTenantNo("MAIN");
                                    row.setVersion(1L);
                                    row.setDeleted(0);
                                    row.setPayNo(plan.orderNo());
                                    row.setSubOrderNo(plan.subOrderNo());
                                    row.setUserNo(plan.party().userNo());
                                    row.setCustomerKind(plan.party().kind());
                                    row.setGuestNo(plan.party().guestNo());
                                    row.setIdentityRevision(plan.party().identityRevision());
                                    row.setEntityNo(plan.economic().entityNo());
                                    row.setStoreNo(plan.economic().storeNo());
                                    row.setCaller("BEAUTY");
                                    row.setOperationNo(plan.operationNo());
                                    row.setCreationKind("COUNTER");
                                    row.setAdmissionKind("MANUAL_OFFLINE");
                                    row.setEndpoint(COUNTER_ENDPOINT);
                                    row.setIdemKey(plan.intentKey());
                                    row.setRequestHash(plan.requestHash());
                                    row.setStockAuthority(plan.stockAuthority());
                                    row.setStockNeedsJson(
                                            json.writeValueAsString(plan.stockNeeds()));
                                    row.setCounterPlanJson(encoded);
                                    row.setPlanHash(hash);
                                    row.setConfirmationNo(
                                            authorization.expected().confirmationNo());
                                    row.setAuthorityNamespaceHash(
                                            authorization.authorityNamespaceHash());
                                    var original =
                                            new Original(
                                                    "MAIN",
                                                    row.getEntityNo(),
                                                    row.getPayNo(),
                                                    plan.stockAuthority(),
                                                    plan.lifecycleVersion(),
                                                    plan.manifestHash(),
                                                    StockDispositionContract.needs(
                                                            plan.stockNeeds()));
                                    row.setReservationOperationNo(
                                            StockDispositionContract.reservationOperationNo(
                                                    original));
                                    row.setReservationState("NOT_SENT");
                                    row.setStatus(OrdCreateIntent.PREPARED);
                                    row.setNextAttemptAt(System.currentTimeMillis() + 30_000L);
                                    try {
                                        intents.insert(row);
                                        return plan;
                                    } catch (DuplicateKeyException duplicate) {
                                        var prior =
                                                intents.selectOne(
                                                        Wrappers.<OrdCreateIntent>lambdaQuery()
                                                                .eq(
                                                                        OrdCreateIntent
                                                                                ::getTenantNo,
                                                                        "MAIN")
                                                                .eq(
                                                                        OrdCreateIntent
                                                                                ::getEndpoint,
                                                                        COUNTER_ENDPOINT)
                                                                .eq(
                                                                        OrdCreateIntent::getIdemKey,
                                                                        plan.intentKey())
                                                                .last("FOR UPDATE"));
                                        if (prior == null
                                                || !"COUNTER".equals(prior.getCreationKind())
                                                || !prior.getRequestHash()
                                                        .equals(plan.requestHash())
                                                || !authorization
                                                        .expected()
                                                        .confirmationNo()
                                                        .equals(prior.getConfirmationNo())
                                                || !authorization
                                                        .authorityNamespaceHash()
                                                        .equals(
                                                                prior
                                                                        .getAuthorityNamespaceHash())) {
                                            throw BizException.of(ErrorCode.CONFLICT);
                                        }
                                        var frozen =
                                                json.readValue(
                                                        prior.getCounterPlanJson(),
                                                        CounterOrderPlan.class);
                                        if (!prior.getPlanHash()
                                                        .equals(CheckoutDigests.hash(json, frozen))
                                                || !plan.party().equals(frozen.party())) {
                                            throw BizException.of(ErrorCode.CONFLICT);
                                        }
                                        return frozen;
                                    }
                                }));
    }

    @Override
    public void recordCounterReservation(String no, long revision, ReservationView observation) {
        var hint =
                DataScopeContext.executeWithoutScope(
                        () ->
                                intents.selectOne(
                                        Wrappers.<OrdCreateIntent>lambdaQuery()
                                                .eq(OrdCreateIntent::getTenantNo, "MAIN")
                                                .eq(OrdCreateIntent::getPayNo, no)
                                                .eq(OrdCreateIntent::getCreationKind, "COUNTER")));
        if (hint == null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        DataScopeContext.executeWithoutScope(
                () ->
                        independent.executeWithoutResult(
                                tx -> {
                                    var op =
                                            counterOperations.lock("BEAUTY", hint.getOperationNo());
                                    if (op == null) {
                                        throw BizException.of(ErrorCode.CONFLICT);
                                    }
                                    counterQuotes.lock(op.getCurrentQuoteNo());
                                    counterConfirmations.lock(op.getConfirmationNo());
                                    var row = intents.lock(no);
                                    if (row == null
                                            || !"COUNTER".equals(row.getCreationKind())
                                            || !row.getOperationNo().equals(op.getOperationNo())
                                            || !Long.valueOf(revision).equals(row.getVersion())) {
                                        throw BizException.of(ErrorCode.CONFLICT);
                                    }
                                    if (OrdCreateIntent.COMMITTED.equals(row.getStatus())) {
                                        return;
                                    }
                                    if (!OrdCreateIntent.PREPARED.equals(row.getStatus())) {
                                        throw BizException.of(ErrorCode.CONFLICT);
                                    }
                                    var plan =
                                            json.readValue(
                                                    row.getCounterPlanJson(),
                                                    CounterOrderPlan.class);
                                    var original =
                                            new Original(
                                                    "MAIN",
                                                    row.getEntityNo(),
                                                    row.getPayNo(),
                                                    plan.stockAuthority(),
                                                    plan.lifecycleVersion(),
                                                    plan.manifestHash(),
                                                    StockDispositionContract.needs(
                                                            plan.stockNeeds()));
                                    if (!row.getPlanHash().equals(CheckoutDigests.hash(json, plan))
                                            || !original.equals(observation.original())
                                            || !row.getReservationOperationNo()
                                                    .equals(observation.reservationOperationNo())) {
                                        throw BizException.of(ErrorCode.CONFLICT);
                                    }
                                    row.setReservationState(observation.outcome());
                                    row.setReservationObservationJson(
                                            json.writeValueAsString(observation));
                                    row.setReservationObservationHash(
                                            observation.observationHash());
                                    row.setNextAttemptAt(System.currentTimeMillis() + 30_000L);
                                    if (intents.updateById(row) != 1) {
                                        throw BizException.of(ErrorCode.CONFLICT);
                                    }
                                }));
    }

    public OrderCreationRecoveryServiceImpl(CreateIntentMapper intents, PaymentMapper payments, StockPort stock,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Value("${shop.inventory.stock-authority:PLATFORM}") String authority) {
        this.intents = intents;
        this.payments = payments;
        this.stock = stock;
        this.json = json;
        this.authority = authority;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String register(String proposed, String buyer, String key, String hash, List<StockPort.SkuQty> needs) {
        if (!(stock instanceof ReservationOwner actual)
                || !Objects.equals(authority, actual.stockAuthority())) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        return independent.execute(
                tx -> {
                    OrdCreateIntent intent = new OrdCreateIntent();
                    intent.setIdentityRevision(1L);
                    intent.setPayNo(proposed);
                    intent.setUserNo(buyer);
                    intent.setEndpoint(ENDPOINT);
                    intent.setIdemKey(key == null || key.isBlank() ? null : key);
                    intent.setRequestHash(hash);
                    intent.setStockAuthority(actual.stockAuthority());
                    intent.setStockNeedsJson(json.writeValueAsString(needs));
                    intent.setStatus(OrdCreateIntent.PREPARED);
                    intent.setNextAttemptAt(System.currentTimeMillis() + LEASE_MS);
                    try {
                        intents.insert(intent);
                        return proposed;
                    } catch (DuplicateKeyException duplicate) {
                        OrdCreateIntent old =
                                intents.selectOne(
                                        Wrappers.<OrdCreateIntent>lambdaQuery()
                                                .eq(OrdCreateIntent::getEndpoint, ENDPOINT)
                                                .eq(OrdCreateIntent::getIdemKey, key)
                                                .last("FOR UPDATE"));
                        if (old == null || !Objects.equals(old.getUserNo(), buyer)) {
                            throw BizException.of(ErrorCode.NOT_FOUND);
                        }
                        if (!Objects.equals(old.getRequestHash(), hash)) {
                            throw BizException.of(ErrorCode.CONFLICT);
                        }
                        return old.getPayNo();
                    }
                });
    }

    @Override
    public void enlist(String payNo) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("creation requires the public order transaction");
        }
        OrdCreateIntent intent = intents.lock(payNo);
        if (intent == null
                || !"LEGACY".equals(intent.getCreationKind())
                || !OrdCreateIntent.PREPARED.equals(intent.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // A failed metadata update remains recoverable by inspecting the original payment.
                recover(payNo, System.currentTimeMillis(), true);
            }
        });
    }

    @Override
    public int recoverAbandoned(long now) {
        List<OrdCreateIntent> pending =
                intents.selectList(
                        Wrappers.<OrdCreateIntent>lambdaQuery()
                                .eq(OrdCreateIntent::getCreationKind, "LEGACY")
                                .in(
                                        OrdCreateIntent::getStatus,
                                        OrdCreateIntent.PREPARED,
                                        OrdCreateIntent.RECOVERING,
                                        OrdCreateIntent.RECOVERY_REQUIRED)
                                .le(OrdCreateIntent::getNextAttemptAt, now)
                                .orderByAsc(OrdCreateIntent::getId)
                                .last("LIMIT 100"));
        int resolved = 0;
        for (OrdCreateIntent intent : pending) {
            if (recover(intent.getPayNo(), now, false)) {
                resolved++;
            }
        }
        return resolved;
    }

    private boolean recover(String no, long now, boolean immediate) {
        try {
            OrdCreateIntent claimed = independent.execute(tx -> claim(no, now, immediate));
            if (claimed == null) {
                return false;
            }
            List<StockPort.SkuQty> needs = json.readValue(claimed.getStockNeedsJson(), new TypeReference<>() {});
            // A fresh platform transaction also avoids joining completed resources during afterCompletion.
            independent.executeWithoutResult(tx -> stock.release(no, needs));
            independent.executeWithoutResult(tx -> finish(no, OrdCreateIntent.RELEASED, null, now));
            return true;
        } catch (RuntimeException failure) {
            log.warn("[create-recovery] {} compensation pending: {}", no, failure.getClass().getSimpleName());
            try {
                independent.executeWithoutResult(tx -> finish(no, OrdCreateIntent.RECOVERY_REQUIRED,
                        failure instanceof BizException biz ? biz.errorCode().name()
                                : failure.getClass().getSimpleName(), now + LEASE_MS));
            } catch (RuntimeException metadataFailure) {
                log.warn("[create-recovery] {} durable claim retained", no);
            }
            return false;
        }
    }

    private OrdCreateIntent claim(String no, long now, boolean immediate) {
        OrdCreateIntent intent = intents.lock(no);
        if (intent == null
                || !"LEGACY".equals(intent.getCreationKind())
                || OrdCreateIntent.COMMITTED.equals(intent.getStatus())
                || OrdCreateIntent.RELEASED.equals(intent.getStatus())) {
            return null;
        }
        OrdOrder payment = payments.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, no).last("FOR UPDATE"));
        if (payment != null) {
            finish(no, OrdCreateIntent.COMMITTED, null, now);
            return null;
        }
        if (!immediate && intent.getNextAttemptAt() > now) {
            return null;
        }
        if (!Objects.equals(intent.getStockAuthority(), authority)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        finish(no, OrdCreateIntent.RECOVERING, null, now + LEASE_MS);
        return intent;
    }

    private void finish(String no, String state, String error, long next) {
        intents.update(null, Wrappers.<OrdCreateIntent>lambdaUpdate().eq(OrdCreateIntent::getPayNo, no)
                .in(OrdCreateIntent::getStatus, OrdCreateIntent.PREPARED, OrdCreateIntent.RECOVERING,
                        OrdCreateIntent.RECOVERY_REQUIRED)
                .set(OrdCreateIntent::getUpdatedAt, LocalDateTime.now()).setSql("version = version + 1")
                .set(OrdCreateIntent::getStatus, state).set(OrdCreateIntent::getErrorCode, error)
                .set(OrdCreateIntent::getNextAttemptAt, next));
    }
}
