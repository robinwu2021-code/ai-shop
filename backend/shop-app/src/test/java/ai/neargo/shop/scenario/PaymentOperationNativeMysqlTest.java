package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.PaymentOperationService;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.paybridge.PaymentReconReconciler;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.trade.service.OrderService;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

/** Real ordinary orders and the real pay database own the tested operation and cash facts. */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class PaymentOperationNativeMysqlTest extends CheckoutFlowFixture {
    @Autowired private PaymentOperationPort operations;
    @Autowired private PaymentOperationService owner;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderService orders;
    @Autowired private ReconService recon;
    @Autowired private PaymentReconReconciler reconciler;
    @MockitoSpyBean private TestPayGateway gateway;

    @Autowired
    @Qualifier("paySqlSessionFactory")
    private SqlSessionFactory paySessions;

    @Autowired
    @Qualifier("payTxManager")
    private PlatformTransactionManager payManager;

    @AfterEach
    void resetChannelObservation() {
        reset(gateway);
    }

    @Autowired
    @Qualifier("payDataSource")
    private DataSource payDataSource;

    @Test
    void preparedReadBeforeAliasCommitRecoversCanonicalReceiptUnderRepeatableRead()
            throws Exception {
        stalePreparedAlias(false);
    }

    @Test
    void preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias() throws Exception {
        stalePreparedAlias(true);
    }

    private void stalePreparedAlias(boolean claim) throws Exception {
        var first = offlineInstruction();
        var receivedAt = Instant.ofEpochMilli(System.currentTimeMillis());
        var receipt = owner.confirmOffline(cash(first, receivedAt));
        assertThat(receipt.phase()).isEqualTo("SUCCEEDED");
        var second = alias(first);
        var gate = new AliasGate();
        paySessions.getConfiguration().addInterceptor(gate);
        Object actualOwner = AopTestUtils.getUltimateTargetObject(owner);
        var template =
                (TransactionTemplate) ReflectionTestUtils.getField(actualOwner, "transactions");
        int originalIsolation = template.getIsolationLevel();
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var binding =
                        executor.submit(
                                () -> {
                                    gate.confirmationThread.set(Thread.currentThread());
                                    return owner.confirmOffline(cash(second, receivedAt));
                                });
                assertThat(gate.prepared.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT phase FROM stl_payment_operation WHERE"
                                            + " operation_no=?",
                                        String.class,
                                        second.operationNo()))
                        .isEqualTo("PREPARED");
                var observation =
                        executor.submit(
                                () -> {
                                    gate.observerThread.set(Thread.currentThread());
                                    return claim
                                            ? owner.claimSend(second.operationNo())
                                            : owner.observeReceipt(second.operationNo());
                                });
                assertThat(gate.readPrepared.await(10, TimeUnit.SECONDS)).isTrue();
                gate.bindAlias.countDown();
                assertThat(binding.get(10, TimeUnit.SECONDS).receiptNo())
                        .isEqualTo(receipt.receiptNo());
                gate.resumeObservation.countDown();
                var result = observation.get(10, TimeUnit.SECONDS);
                assertThat(gate.observerGuardAttempts.get()).isEqualTo(2);
                var expectedLocks = new ArrayList<String>();
                expectedLocks.add(second.operationNo());
                expectedLocks.addAll(
                        List.of(first.operationNo(), second.operationNo()).stream()
                                .sorted()
                                .toList());
                assertThat(gate.observerLocks).containsExactlyElementsOf(expectedLocks);
                if (claim) {
                    assertThat(result).isEqualTo(Optional.empty());
                } else {
                    var view = (PaymentOperationPort.PaymentOperationView) result;
                    assertThat(view.canonicalOperationNo()).isEqualTo(first.operationNo());
                    assertThat(view.receiptNo()).isEqualTo(receipt.receiptNo());
                }
                assertThat(operations.query(second.operationNo()).receiptNo())
                        .isEqualTo(receipt.receiptNo());
                assertThat(operations.initiate(second.operationNo()).receiptNo())
                        .isEqualTo(receipt.receiptNo());
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT phase FROM stl_payment_operation WHERE"
                                            + " operation_no=?",
                                        String.class,
                                        second.operationNo()))
                        .isEqualTo("ALIASED");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT phase FROM stl_payment_operation WHERE"
                                            + " operation_no=?",
                                        String.class,
                                        first.operationNo()))
                        .isEqualTo("SUCCEEDED");
                assertOnePayment(first.orderNo());
            } finally {
                gate.bindAlias.countDown();
                gate.resumeObservation.countDown();
                gate.enabled = false;
            }
        } finally {
            template.setIsolationLevel(originalIsolation);
        }
    }

    private PaymentOperationPort.OfflinePaymentReceiptReq cash(
            PaymentOperationPort.PaymentOperationReq request, Instant receivedAt) {
        return new PaymentOperationPort.OfflinePaymentReceiptReq(
                request,
                "CASH",
                null,
                actor,
                "MERCHANT",
                "MCH",
                actor,
                receivedAt,
                "actual receipt",
                null);
    }

    private PaymentOperationPort.PaymentOperationReq offlineInstruction() {
        jdbc.update("UPDATE mch_store SET offline_pay_enabled=1 WHERE store_no=?", store);
        jdbc.update(
                "UPDATE prd_goods SET pay_modes='[\"ONLINE\",\"OFFLINE\"]' WHERE goods_no=?",
                goods);
        jdbc.update(
                "INSERT INTO"
                    + " mch_qualification(qual_no,entity_no,qual_type,qual_name,status,created_at,updated_at)"
                    + " VALUES (?,?,'BUSINESS_LICENSE','license','VALID',NOW(),NOW())",
                "QL-" + sku,
                entity);
        var source = request(null);
        var quote =
                asMerchant(
                        () ->
                                checkout.prepare(
                                        scope,
                                        new CheckoutPort.PrepareCheckoutReq(
                                                source.operation(),
                                                source.requestKey(),
                                                source.customerRef(),
                                                source.scopeRevision(),
                                                source.scopeHash(),
                                                source.lines(),
                                                "OFFLINE",
                                                null,
                                                Map.of(),
                                                null)));
        var confirmation = asBuyer(() -> confirm(quote));
        var placed = asBuyer(() -> checkout.place(place(quote, confirmation)));
        return new PaymentOperationPort.PaymentOperationReq(
                "MAIN",
                "PO" + UUID.randomUUID(),
                "a".repeat(64),
                placed.orderNo(),
                buyer,
                entity,
                store,
                orders.offlineReceiptInstructionForCheckout(placed.orderNo(), buyer));
    }

    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class})
    })
    static class AliasGate implements Interceptor {
        volatile boolean enabled = true;
        final AtomicReference<Thread> confirmationThread = new AtomicReference<>();
        final AtomicReference<Thread> observerThread = new AtomicReference<>();
        final CountDownLatch prepared = new CountDownLatch(1);
        final CountDownLatch bindAlias = new CountDownLatch(1);
        final CountDownLatch readPrepared = new CountDownLatch(1);
        final CountDownLatch resumeObservation = new CountDownLatch(1);
        final AtomicBoolean captured = new AtomicBoolean();
        final AtomicInteger observerGuardAttempts = new AtomicInteger();
        final List<String> observerLocks = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (enabled
                    && Thread.currentThread() == confirmationThread.get()
                    && statement.endsWith("PaymentOrderGuardMapper.ensure")) {
                prepared.countDown();
                assertThat(bindAlias.await(10, TimeUnit.SECONDS)).isTrue();
            }
            if (enabled && Thread.currentThread() == observerThread.get()) {
                if (statement.endsWith("PaymentOrderGuardMapper.ensure")) {
                    observerGuardAttempts.incrementAndGet();
                }
                if (statement.endsWith("PaymentOperationMapper.lock")) {
                    observerLocks.add((String) ((Map<?, ?>) invocation.getArgs()[1]).get("no"));
                }
            }
            Object actual = invocation.proceed();
            if (enabled
                    && Thread.currentThread() == observerThread.get()
                    && statement.endsWith("PaymentOperationMapper.find")
                    && captured.compareAndSet(false, true)) {
                assertThat(
                                ((Executor) invocation.getTarget())
                                        .getTransaction()
                                        .getConnection()
                                        .getTransactionIsolation())
                        .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                readPrepared.countDown();
                assertThat(resumeObservation.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return actual;
        }
    }

    @Test
    void canonicalOperationsRejectCallerTransactionsBeforeTakingLocks() {
        var request = instruction();
        operations.prepare(request);
        var caller = new TransactionTemplate(payManager);
        caller.executeWithoutResult(
                tx -> {
                    assertThatThrownBy(() -> owner.observeReceipt(request.operationNo()))
                            .isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(() -> owner.claimSend(request.operationNo()))
                            .isInstanceOf(IllegalStateException.class);
                });
        assertThat(operations.query(request.operationNo()).phase()).isEqualTo("PREPARED");
    }

    @Test
    void preparedQueryDoesNotDispatch() {
        var request = instruction();
        var prepared = operations.prepare(request);
        assertThat(prepared.phase()).isEqualTo("PREPARED");
        assertThat(operations.query(request.operationNo()).outTradeNo()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                Long.class,
                                request.orderNo()))
                .isZero();
    }

    @Test
    void aliasWinningTheFirstSendReturnsItsOwnIdentityAndCanonicalOriginalNumber() {
        var canonical = instruction();
        var alias = alias(canonical);
        operations.prepare(canonical);
        operations.prepare(alias);
        var opened = owner.open(canonical.operationNo());
        assertThat(opened.phase()).isEqualTo("OPENED");
        var sentByAlias = operations.initiate(alias.operationNo());
        assertThat(sentByAlias.operationNo()).isEqualTo(alias.operationNo());
        assertThat(sentByAlias.requestHash()).isEqualTo(alias.requestHash());
        assertThat(sentByAlias.canonicalOperationNo()).isEqualTo(canonical.operationNo());
        assertThat(sentByAlias.outTradeNo()).isEqualTo(opened.outTradeNo());
        assertThat(sentByAlias.phase()).isEqualTo("PAYABLE");
        assertThat(sentByAlias.payParams()).isNotEmpty();
        var replay = operations.initiate(canonical.operationNo());
        assertThat(replay.operationNo()).isEqualTo(canonical.operationNo());
        assertThat(replay.outTradeNo()).isEqualTo(sentByAlias.outTradeNo());
        assertThat(replay.payParams()).isEqualTo(sentByAlias.payParams());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?"
                                        + " AND send_started_at IS NOT NULL",
                                Long.class,
                                canonical.orderNo()))
                .isEqualTo(1L);
        assertOnePayment(canonical.orderNo());
    }

    @Test
    void aliasWinningUncertainDispatchKeepsItsIdentityAndOriginalPendingPayment() {
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw new IllegalStateException("isolated accepted response lost");
                        })
                .when(gateway)
                .prepay(any());
        var result = aliasWinsDispatch("UNKNOWN");
        assertThat(gateway.query(result.outTradeNo()).found()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM stl_payment WHERE out_trade_no=?",
                                String.class,
                                result.outTradeNo()))
                .isEqualTo("PENDING");
    }

    @Test
    void aliasWinningProvenUnsentDispatchKeepsItsIdentityAndRejectedOriginal() {
        doReturn(PayGateway.PrepayResult.notSent("isolated local rejection"))
                .when(gateway)
                .prepay(any());
        var result = aliasWinsDispatch("REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM stl_payment WHERE out_trade_no=?",
                                String.class,
                                result.outTradeNo()))
                .isEqualTo("CLOSED");
    }

    private PaymentOperationPort.PaymentOperationView aliasWinsDispatch(String phase) {
        var canonical = instruction();
        var alias = alias(canonical);
        operations.prepare(canonical);
        operations.prepare(alias);
        var opened = owner.open(canonical.operationNo());
        var result = operations.initiate(alias.operationNo());
        assertThat(result.operationNo()).isEqualTo(alias.operationNo());
        assertThat(result.requestHash()).isEqualTo(alias.requestHash());
        assertThat(result.canonicalOperationNo()).isEqualTo(canonical.operationNo());
        assertThat(result.outTradeNo()).isEqualTo(opened.outTradeNo());
        assertThat(result.phase()).isEqualTo(phase);
        assertThat(owner.claimSend(alias.operationNo())).isEmpty();
        assertOnePayment(canonical.orderNo());
        return result;
    }

    @Test
    void differentKeysShareOneCanonicalAttempt() {
        var request = instruction();
        operations.prepare(request);
        var first = operations.initiate(request.operationNo());
        var alias =
                new PaymentOperationPort.PaymentOperationReq(
                        "MAIN",
                        "PO" + UUID.randomUUID(),
                        "b".repeat(64),
                        request.orderNo(),
                        buyer,
                        entity,
                        store,
                        request.payment());
        operations.prepare(alias);
        var second = operations.initiate(alias.operationNo());
        assertThat(second.outTradeNo()).isEqualTo(first.outTradeNo());
        assertThat(second.canonicalOperationNo()).isEqualTo(first.canonicalOperationNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                Long.class,
                                request.orderNo()))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?"
                                        + " AND send_started_at IS NOT NULL",
                                Long.class,
                                request.orderNo()))
                .isEqualTo(1L);
        assertThatThrownBy(
                        () ->
                                operations.prepare(
                                        new PaymentOperationPort.PaymentOperationReq(
                                                "MAIN",
                                                request.operationNo(),
                                                "c".repeat(64),
                                                request.orderNo(),
                                                buyer,
                                                entity,
                                                store,
                                                request.payment())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void trustedCheckoutInstructionCannotCrossItsFrozenStoreOrOmitOnlineRoute() {
        var request = instruction();
        assertThatThrownBy(
                        () ->
                                new PaymentOperationPort.PaymentOperationReq(
                                        "MAIN",
                                        request.operationNo(),
                                        request.requestHash(),
                                        request.orderNo(),
                                        buyer,
                                        entity,
                                        "OTHER_STORE",
                                        request.payment()))
                .isInstanceOf(IllegalArgumentException.class);
        var routeMissing =
                new SettlePort.PaymentOpen(request.orderNo(), buyer, entity, "TEST", 303, "actual");
        assertThatThrownBy(
                        () ->
                                new PaymentOperationPort.PaymentOperationReq(
                                        "MAIN",
                                        request.operationNo(),
                                        request.requestHash(),
                                        request.orderNo(),
                                        buyer,
                                        entity,
                                        store,
                                        routeMissing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim() throws Exception {
        var first = instruction();
        var second = alias(first);
        operations.prepare(first);
        operations.prepare(second);
        var opened =
                concurrently(
                        () -> owner.open(first.operationNo()),
                        () -> owner.open(second.operationNo()));
        assertThat(opened.get(0).canonicalOperationNo())
                .isEqualTo(opened.get(1).canonicalOperationNo());
        assertThat(opened.get(0).outTradeNo()).isEqualTo(opened.get(1).outTradeNo());
        var claims =
                concurrently(
                        () -> owner.claimSend(first.operationNo()),
                        () -> owner.claimSend(second.operationNo()));
        assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertOnePayment(first.orderNo());
    }

    @Test
    void oldOpenAndNewOpenUseOneGlobalGuardDespiteHistoricalAuditTenant() throws Exception {
        var request = instruction();
        operations.prepare(request);
        var opened =
                concurrently(
                        () -> ledger.open(request.payment()),
                        () -> owner.open(request.operationNo()).outTradeNo());
        assertThat(opened.get(0)).isEqualTo(opened.get(1));
        jdbc.update(
                "UPDATE stl_payment SET tenant_no='HISTORICAL' WHERE order_no=?",
                request.orderNo());
        assertThat(ledger.open(request.payment())).isEqualTo(opened.getFirst());
        assertThat(owner.open(aliasPrepared(request).operationNo()).outTradeNo())
                .isEqualTo(opened.getFirst());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment_order_guard WHERE order_no=?",
                                Long.class,
                                request.orderNo()))
                .isEqualTo(1L);
        assertOnePayment(request.orderNo());
    }

    @Test
    void successfulReceiptPreventsBothOldAndNewOpenFromCreatingAnotherChargeableAttempt() {
        var request = instruction();
        operations.prepare(request);
        var opened = owner.open(request.operationNo());
        ledger.settle(
                new SettlePort.PaymentSettled(
                        opened.outTradeNo(),
                        "TEST",
                        "ACTUAL-" + sku,
                        303,
                        System.currentTimeMillis(),
                        "CNY"));
        assertThatThrownBy(() -> ledger.open(request.payment()))
                .isInstanceOf(RuntimeException.class);
        var second = aliasPrepared(request);
        assertThat(owner.open(second.operationNo()).outTradeNo()).isEqualTo(opened.outTradeNo());
        assertThat(owner.claimSend(second.operationNo())).isEmpty();
        assertThat(operations.query(second.operationNo()).receiptNo()).startsWith("RC");
        assertOnePayment(request.orderNo());
    }

    @Test
    void sendingAndUnknownNotFoundCannotBeClosedByLegacyCloseOrReconciliation() {
        var request = instruction();
        operations.prepare(request);
        var opened = owner.open(request.operationNo());
        var ticket = owner.claimSend(request.operationNo()).orElseThrow();
        for (boolean unknown : List.of(false, true)) {
            if (unknown) {
                owner.recordUnknown(ticket, "RESPONSE_LOST");
            }
            ledger.close(opened.outTradeNo(), "old close");
            jdbc.update(
                    "UPDATE stl_payment SET created_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE"
                            + " out_trade_no=?",
                    opened.outTradeNo());
            var findings = recon.checkStalePayments(System.currentTimeMillis());
            assertThat(findings)
                    .anySatisfy(
                            finding -> {
                                assertThat(finding.outTradeNo()).isEqualTo(opened.outTradeNo());
                                assertThat(finding.notFound()).isTrue();
                            });
            reconciler.scan(System.currentTimeMillis());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM stl_payment WHERE out_trade_no=?",
                                    String.class,
                                    opened.outTradeNo()))
                    .isEqualTo("PENDING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ord_order WHERE order_no=?",
                                    String.class,
                                    request.orderNo()))
                    .isEqualTo("WAIT_PAY");
        }
        assertThat(owner.open(aliasPrepared(request).operationNo()).outTradeNo())
                .isEqualTo(opened.outTradeNo());
        assertOnePayment(request.orderNo());
    }

    @Test
    void provenNotSentKeepsOriginalRejectedMappingAndPermitsOnlyNewOperationToAllocate() {
        var request = instruction();
        operations.prepare(request);
        var opened = owner.open(request.operationNo());
        var ticket = owner.claimSend(request.operationNo()).orElseThrow();
        owner.rejectNotSent(ticket, PaymentOperationService.NotSentReason.LOCAL_PRECONDITION);
        assertThat(operations.query(request.operationNo()).phase()).isEqualTo("REJECTED");
        assertThat(owner.open(request.operationNo()).outTradeNo()).isEqualTo(opened.outTradeNo());
        assertThat(owner.claimSend(request.operationNo())).isEmpty();
        var replacement = owner.open(aliasPrepared(request).operationNo());
        assertThat(replacement.outTradeNo()).isNotEqualTo(opened.outTradeNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                        + " status='PENDING'",
                                Long.class,
                                request.orderNo()))
                .isEqualTo(1L);
    }

    @Test
    void offlineLedgerIsExcludedFromExternalChannelReconciliation() {
        var request = instruction();
        var offline =
                new SettlePort.PaymentOpen(
                        request.orderNo(), buyer, entity, "OFFLINE", 303, "actual cash");
        String number = ledger.open(offline);
        jdbc.update(
                "UPDATE stl_payment SET created_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE"
                        + " out_trade_no=?",
                number);
        assertThat(recon.checkStalePayments(System.currentTimeMillis()))
                .noneMatch(finding -> number.equals(finding.outTradeNo()));
    }

    @Test
    void payAndPlatformUseDifferentActualConnectionsToTheSameAuthoritativeSchema()
            throws Exception {
        assertThat(payDataSource).isNotSameAs(dataSource);
        try (var pay = payDataSource.getConnection();
                var platform = dataSource.getConnection();
                var payStatement = pay.createStatement();
                var platformStatement = platform.createStatement();
                var payRow = payStatement.executeQuery("SELECT CONNECTION_ID(),DATABASE()");
                var platformRow =
                        platformStatement.executeQuery("SELECT CONNECTION_ID(),DATABASE()")) {
            assertThat(payRow.next()).isTrue();
            assertThat(platformRow.next()).isTrue();
            assertThat(payRow.getLong(1)).isNotEqualTo(platformRow.getLong(1));
            assertThat(payRow.getString(2)).isEqualTo(platformRow.getString(2));
        }
    }

    private PaymentOperationPort.PaymentOperationReq aliasPrepared(
            PaymentOperationPort.PaymentOperationReq original) {
        var alias = alias(original);
        operations.prepare(alias);
        return alias;
    }

    private PaymentOperationPort.PaymentOperationReq alias(
            PaymentOperationPort.PaymentOperationReq original) {
        return new PaymentOperationPort.PaymentOperationReq(
                "MAIN",
                "PO" + UUID.randomUUID(),
                "b".repeat(64),
                original.orderNo(),
                buyer,
                entity,
                store,
                original.payment());
    }

    private void assertOnePayment(String orderNo) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                        + " direction='PAY'",
                                Long.class,
                                orderNo))
                .isEqualTo(1L);
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return first.call();
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return second.call();
                            });
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private PaymentOperationPort.PaymentOperationReq instruction() {
        String payMerchant = "QP-" + sku;
        jdbc.update("UPDATE mch_entity SET funds_mode='DIRECT' WHERE entity_no=?", entity);
        jdbc.update("UPDATE mch_store SET pay_merchant_no=? WHERE store_no=?", payMerchant, store);
        jdbc.update(
                "INSERT INTO"
                    + " mch_payment_merchant(entity_no,store_no,pay_merchant_no,pay_channel,"
                        + "apply_status,sub_mchid,created_at,updated_at)"
                    + " VALUES (?,?,?,'TEST','ACTIVE','test-owner',NOW(),NOW())",
                entity,
                store,
                payMerchant);
        var quote = asMerchant(() -> checkout.prepare(scope, request(null)));
        var confirmation = asBuyer(() -> confirm(quote));
        var placed = asBuyer(() -> checkout.place(place(quote, confirmation)));
        return new PaymentOperationPort.PaymentOperationReq(
                "MAIN",
                "PO" + UUID.randomUUID(),
                "a".repeat(64),
                placed.orderNo(),
                buyer,
                entity,
                store,
                orders.paymentInstructionForCheckout(placed.orderNo(), buyer, "TEST"));
    }
}
