package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.support.OrderReceiptFixture;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.service.OrderReceiptService;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

/** Synchronize only real lock entry, then exercise production receipt transactions and persisted facts. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.test.url:jdbc:h2:mem:receipt-concurrency;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
@ActiveProfiles("test")
class OrderReceiptConcurrencyFlowTest {
    private static final String BUYER = "RC-RACE-BUYER";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderShippingService shipping;
    @Autowired private OrderReceiptService receipts;
    @Autowired private OrderReceiptCompleter auto;
    @Autowired private SqlSessionTemplate sessions;
    @MockitoSpyBean private StatusCasMapper locks;

    @Test
    void concurrentManualAndAutoCompleteAndLogOnlyOnce() throws Exception {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis();
        OrderReceiptFixture.due(jdbc, sub, now - 20L * 86_400_000L);
        synchronizeLockEntry(sub);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> receipts.confirmCurrent(sub, OrderActor.buyer(BUYER)));
            var two = pool.submit(() -> auto.completeAuto(sub, now, 15, 7));
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        }
        assertCompletedOnce(sub);
        assertThat(packageStatus(shipment)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE aggregate_no=? "
                + "AND aggregate_type='SHIPMENT' AND status='RECEIVED'", Long.class, shipment)).isEqualTo(1);
    }

    @Test
    void lastTwoHistoricalPackagesConcurrentlyConverge() throws Exception {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String first = OrderReceiptFixture.ship(shipping, sub);
        String second = OrderReceiptFixture.extraPackage(jdbc, sub);
        String last = OrderReceiptFixture.extraPackage(jdbc, sub);
        receipts.confirmReceipt(sub, first, OrderActor.buyer(BUYER));
        assertThat(status(sub)).isEqualTo("FULFILLING");
        synchronizeLockEntry(sub);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> receipts.confirmReceipt(sub, second, OrderActor.buyer(BUYER)));
            var two = pool.submit(() -> receipts.confirmReceipt(sub, last, OrderActor.buyer(BUYER)));
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        }
        assertCompletedOnce(sub);
        assertThat(packageStatus(second)).isEqualTo("RECEIVED");
        assertThat(packageStatus(last)).isEqualTo("RECEIVED");
    }

    @Test
    void wholeOrderReceiptIncludesAllHistoricalLivePackagesAtomically() {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String first = OrderReceiptFixture.ship(shipping, sub);
        String last = OrderReceiptFixture.extraPackage(jdbc, sub);
        receipts.confirmCurrent(sub, OrderActor.buyer(BUYER));
        assertCompletedOnce(sub);
        assertThat(packageStatus(first)).isEqualTo("RECEIVED");
        assertThat(packageStatus(last)).isEqualTo("RECEIVED");
    }

    @Test
    void explicitBuyerCannotBypassActualParentOwnershipWithoutAmbientScope() {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        jdbc.update("UPDATE ord_order SET user_no='RC-OTHER-BUYER' WHERE order_no=?", "O-" + sub);
        assertThatThrownBy(() -> receipts.confirmReceipt(sub, shipment, OrderActor.buyer(BUYER)))
                .isInstanceOf(BizException.class);
        assertThat(packageStatus(shipment)).isEqualTo("CREATED");
        assertThat(status(sub)).isEqualTo("FULFILLING");
    }

    @Test
    void currentRefundedStatePreventsAutoReceiptAndPackageChanges() {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis();
        OrderReceiptFixture.due(jdbc, sub, now - 20L * 86_400_000L);
        jdbc.update("UPDATE ord_sub_order SET status='REFUNDED' WHERE sub_order_no=?", sub);
        assertThat(auto.completeAuto(sub, now, 15, 7)).isFalse();
        assertThat(packageStatus(shipment)).isEqualTo("CREATED");
        assertThat(status(sub)).isEqualTo("REFUNDED");
    }

    private void synchronizeLockEntry(String sub) {
        CyclicBarrier entry = new CyclicBarrier(2);
        Set<Long> arrived = ConcurrentHashMap.newKeySet();
        StatusCasMapper realLocks = sessions.getMapper(StatusCasMapper.class);
        doAnswer(invocation -> {
            if (arrived.add(Thread.currentThread().threadId())) {
                entry.await(10, TimeUnit.SECONDS);
            }
            return realLocks.lockOrder(sub);
        }).when(locks).lockOrder(sub);
    }

    private void assertCompletedOnce(String sub) {
        assertThat(status(sub)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE sub_order_no=? "
                + "AND aggregate_type='ORDER' AND status='COMPLETED'", Long.class, sub)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completed_at FROM ord_sub_order WHERE sub_order_no=?",
                Long.class, sub)).isPositive();
    }
    private String status(String no) {
        return jdbc.queryForObject("SELECT status FROM ord_sub_order WHERE sub_order_no=?", String.class, no);
    }
    private String packageStatus(String no) {
        return jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, no);
    }
}
