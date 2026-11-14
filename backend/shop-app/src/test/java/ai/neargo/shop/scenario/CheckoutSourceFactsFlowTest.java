package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.trade.order.mapper.RefundLedgerMapper;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutSourceFactsFlowTest extends CheckoutFlowFixture {
    @Autowired private RefundLedgerMapper refunds;

    @Autowired
    @Qualifier("transactionManager")
    private PlatformTransactionManager manager;

    @Test
    void unknownReceiptNeverBecomesZeroMoney() {
        var result = created();
        var facts = source(result);
        assertThat(facts)
                .as("source must return durable owner-backed historical facts")
                .isNotNull();
        assertThat(facts.financialState()).isEqualTo("UNKNOWN");
        assertThat(facts.receivedMinor()).isNull();
        assertThat(facts.outstandingMinor()).isNull();
        assertThat(facts.sourceRevision()).isZero();
        assertThat(facts.sourceHash()).isNull();
        assertThat(facts.lines()).isEmpty();
        assertThat(facts.payments()).isEmpty();
        assertThat(facts.executionState()).isEqualTo("BLOCKED");
    }

    @Test
    void billUsesHistoricalOwnerMoneyAndStableBusinessLines() {
        var result = created();
        receipt(result, 303);
        jdbc.update("UPDATE prd_sku SET price=999 WHERE sku_no=?", sku);
        var first = source(result);
        assertThat(first).as("source must resolve the original real order").isNotNull();
        assertThat(first.dueMinor()).isEqualTo(303);
        assertThat(first.receivedMinor()).isEqualTo(303);
        assertThat(first.outstandingMinor()).isZero();
        assertThat(first.financialState()).isEqualTo("PAID");
        assertThat(first.lines())
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.sourceLineNo()).startsWith("CL");
                            assertThat(line.clientLineRef()).isEqualTo("service-line");
                            assertThat(line.orderedQuantity()).isEqualTo(3);
                            assertThat(line.refundableQuantity()).isEqualTo(3);
                            assertThat(line.uom()).isEqualTo("COUNT");
                            assertThat(line.dueMinor()).isEqualTo(303);
                        });
        assertThat(first.payments())
                .singleElement()
                .satisfies(
                        payment -> {
                            assertThat(payment.paymentNo()).isEqualTo("P-" + sku);
                            assertThat(payment.tradeNo()).isEqualTo("T-" + sku);
                            assertThat(payment.outTradeNo()).isNull();
                            assertThat(payment.paymentOperationNo()).isNull();
                            assertThat(payment.receiptNo()).isNull();
                        });
        var replay = source(result);
        assertThat(replay.sourceRevision()).isEqualTo(first.sourceRevision());
        assertThat(replay.sourceHash()).isEqualTo(first.sourceHash());
        assertThat(replay.lines()).isEqualTo(first.lines());
    }

    @Test
    void refundWithoutVersionIncrementChangesSourceHashAndAvailableQuantity() {
        var result = created();
        receipt(result, 303);
        var first = source(result);
        assertThat(first).isNotNull();
        Long version =
                jdbc.queryForObject(
                        "SELECT version FROM ord_sub_order WHERE sub_order_no=?",
                        Long.class,
                        result.subOrderNo());
        Long item =
                jdbc.queryForObject(
                        "SELECT id FROM ord_item WHERE sub_order_no=?",
                        Long.class,
                        result.subOrderNo());
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            assertThat(refunds.addItemRefund(item, 1, 101)).isEqualTo(1);
                            assertThat(refunds.addSubRefund(result.subOrderNo(), 101)).isEqualTo(1);
                        });
        var second = source(result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM ord_sub_order WHERE sub_order_no=?",
                                Long.class,
                                result.subOrderNo()))
                .isEqualTo(version);
        assertThat(second.sourceRevision()).isEqualTo(first.sourceRevision() + 1);
        assertThat(second.sourceHash()).isNotEqualTo(first.sourceHash());
        assertThat(second.financialState()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(second.dueMinor()).isEqualTo(303);
        assertThat(second.receivedMinor()).isEqualTo(202);
        assertThat(second.lines())
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.sourceLineNo())
                                    .isEqualTo(first.lines().get(0).sourceLineNo());
                            assertThat(line.refundableQuantity()).isEqualTo(2);
                            assertThat(line.dueMinor()).isEqualTo(303);
                        });
    }

    @Test
    void unknownKeepsLastVerifiedPairAndReturnsNoConsumableLines() {
        var result = created();
        receipt(result, 303);
        var verified = source(result);
        assertThat(verified).isNotNull();
        jdbc.update("UPDATE stl_payment SET status='FAILED' WHERE payment_no=?", "P-" + sku);
        var unknown = source(result);
        assertThat(unknown.financialState()).isEqualTo("UNKNOWN");
        assertThat(unknown.sourceRevision()).isEqualTo(verified.sourceRevision());
        assertThat(unknown.sourceHash()).isEqualTo(verified.sourceHash());
        assertThat(unknown.lines()).isEmpty();
        assertThat(unknown.payments()).isEmpty();
        assertThat(unknown.receivedMinor()).isNull();
        assertThat(unknown.executionState()).isEqualTo("BLOCKED");
    }

    @Test
    void mismatchedReceiptDoesNotBecomeReceivedMoney() {
        var result = created();
        receipt(result, 302);
        var facts = source(result);
        assertThat(facts).isNotNull();
        assertThat(facts.financialState()).isEqualTo("UNKNOWN");
        assertThat(facts.receivedMinor()).isNull();
        assertThat(facts.lines()).isEmpty();
    }

    @Test
    void cancelledOrderHasZeroAvailableQuantity() {
        var result = created();
        receipt(result, 303);
        jdbc.update("UPDATE ord_order SET status='CANCELLED' WHERE order_no=?", result.orderNo());
        var facts = source(result);
        assertThat(facts).isNotNull();
        assertThat(facts.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.refundableQuantity()).isZero());
        assertThat(facts.executionState()).isEqualTo("BLOCKED");
        assertThat(facts.outstandingMinor()).isZero();
    }

    @Test
    void sourceCannotSubstituteAnotherSubOrderOrJoinAnOuterSnapshot() {
        var result = created();
        receipt(result, 303);
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                checkout.source(
                                                        scope,
                                                        new CheckoutPort.SourceLookup(
                                                                result.operation(),
                                                                "foreign-sub"))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                new TransactionTemplate(manager)
                                                        .execute(
                                                                status ->
                                                                        checkout.source(
                                                                                scope,
                                                                                new CheckoutPort
                                                                                        .SourceLookup(
                                                                                        result
                                                                                                .operation(),
                                                                                        result
                                                                                                .subOrderNo())))))
                .isInstanceOf(BizException.class);
    }

    private CheckoutPort.CheckoutResult created() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        return asBuyer(() -> checkout.place(place(q, c)));
    }

    private CheckoutPort.CheckoutSourceFacts source(CheckoutPort.CheckoutResult result) {
        return asMerchant(
                () ->
                        checkout.source(
                                scope,
                                new CheckoutPort.SourceLookup(
                                        result.operation(), result.subOrderNo())));
    }

    private void receipt(CheckoutPort.CheckoutResult result, long amount) {
        jdbc.update(
                "UPDATE ord_order SET status='PAID',pay_channel='WECHAT',pay_trade_no=? WHERE"
                        + " order_no=?",
                "T-" + sku,
                result.orderNo());
        jdbc.update(
                "UPDATE ord_sub_order SET status='FULFILLING' WHERE sub_order_no=?",
                result.subOrderNo());
        jdbc.update(
                "INSERT INTO"
                        + " stl_payment(payment_no,direction,order_no,user_no,pay_channel,amount_minor,"
                          + "currency,status,trade_no,created_at,updated_at)"
                        + " VALUES (?,'PAY',?,?,'WECHAT',?,'CNY','SUCCESS',?,NOW(),NOW())",
                "P-" + sku,
                result.orderNo(),
                buyer,
                amount,
                "T-" + sku);
    }

    @Autowired
    @Qualifier("sqlSessionFactory")
    private SqlSessionFactory sessions;

    @Test
    void refundBetweenFactReadsAndSerializedObservationNeverMixesSnapshots() throws Exception {
        var result = created();
        receipt(result, 303);
        Long item =
                jdbc.queryForObject(
                        "SELECT id FROM ord_item WHERE sub_order_no=?",
                        Long.class,
                        result.subOrderNo());
        var gate = new SnapshotGate();
        sessions.getConfiguration().addInterceptor(gate);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var original = threads.submit(() -> source(result));
            Future<CheckoutPort.CheckoutSourceFacts> later = null;
            try {
                assertThat(gate.snapshot.await(10, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager)
                        .executeWithoutResult(
                                status -> {
                                    assertThat(refunds.addItemRefund(item, 1, 101)).isEqualTo(1);
                                    assertThat(refunds.addSubRefund(result.subOrderNo(), 101))
                                            .isEqualTo(1);
                                });
                later = threads.submit(() -> source(result));
                assertThat(gate.enteredTwice.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(later.isDone())
                        .as("later source cannot publish ahead of the first observation lock")
                        .isFalse();
            } finally {
                gate.release.countDown();
            }
            var first = original.get(10, TimeUnit.SECONDS);
            var second = later.get(10, TimeUnit.SECONDS);
            assertThat(first.receivedMinor()).isEqualTo(303);
            assertThat(first.lines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.refundableQuantity()).isEqualTo(3));
            assertThat(second.receivedMinor()).isEqualTo(202);
            assertThat(second.lines())
                    .singleElement()
                    .satisfies(line -> assertThat(line.refundableQuantity()).isEqualTo(2));
            assertThat(second.sourceRevision()).isEqualTo(first.sourceRevision() + 1);
            assertThat(source(result).sourceHash()).isEqualTo(second.sourceHash());
            assertThat(gate.firstStatement).endsWith("CheckoutMappers$OperationMapper.lock");
        } finally {
            gate.enabled = false;
            gate.release.countDown();
        }
    }

    @Test
    void openAfterSaleBlocksExecutionAndCorruptedMappingBecomesUnknown() {
        var result = created();
        receipt(result, 303);
        var original = source(result);
        jdbc.update(
                "INSERT INTO"
                        + " ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,"
                          + "status,reason,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'REFUND_ONLY','APPLIED','OTHER',NOW(),NOW())",
                "AS-" + sku,
                result.subOrderNo(),
                result.orderNo(),
                buyer,
                entity);
        var disputed = source(result);
        assertThat(disputed.executionState()).isEqualTo("BLOCKED");
        assertThat(disputed.sourceRevision()).isGreaterThan(original.sourceRevision());
        jdbc.update(
                "UPDATE ord_item SET sku_no='changed-historical-sku' WHERE sub_order_no=?",
                result.subOrderNo());
        var corrupt = source(result);
        assertThat(corrupt.financialState()).isEqualTo("UNKNOWN");
        assertThat(corrupt.sourceHash()).isEqualTo(disputed.sourceHash());
        assertThat(corrupt.lines()).isEmpty();
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "query",
                    args = {
                        MappedStatement.class,
                        Object.class,
                        RowBounds.class,
                        ResultHandler.class
                    }))
    static class SnapshotGate implements Interceptor {
        volatile boolean enabled = true;
        volatile String firstStatement;
        final CountDownLatch snapshot = new CountDownLatch(1);
        final CountDownLatch enteredTwice = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean captured = new AtomicBoolean();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            boolean observe =
                    enabled
                            && TransactionSynchronizationManager.isActualTransactionActive()
                            && Arrays.stream(Thread.currentThread().getStackTrace())
                                    .anyMatch(
                                            frame ->
                                                    frame.getClassName()
                                                                    .equals(
                                                                            "ai.neargo.shop.trade.service.impl."
                            + "CheckoutServiceImpl")
                                                            && frame.getMethodName()
                                                                    .equals("sourceSnapshot"));
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (observe) {
                if (firstStatement == null) {
                    firstStatement = statement;
                }
                if (statement.endsWith("CheckoutMappers$OperationMapper.lock")) {
                    enteredTwice.countDown();
                }
            }
            Object actual = invocation.proceed();
            if (observe
                    && statement.endsWith("TradeMappers$OrderMapper.selectList")
                    && captured.compareAndSet(false, true)) {
                snapshot.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("source snapshot release timeout");
                }
            }
            return actual;
        }
    }
}
