package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.paybridge.ShippingUploadQueue;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Real queue read/write interleaving must not acknowledge a replacement event while stranding old source state. */
@SpringBootTest
@ActiveProfiles("test")
class ShippingUploadEnqueueRaceFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private WxShippingUploadService uploads;
    @Autowired private ShippingUploadQueue actualQueue;
    @Autowired private ShippingUploadMapper rows;
    @Autowired private WxShippingPort originalChannel;
    @Autowired private OutboxDispatcher dispatcher;

    @Test
    void replacementEventRefreshCannotLoseToOriginalUploadCompletion() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        String oldSource = ship(sub, "A");
        drain();
        TrdShippingUpload old = row(sub);
        Latches gates = new Latches();
        ShippingUploadQueue observed = observedQueue("O-" + sub, gates);
        ReflectionTestUtils.setField(uploads, "queue", observed);
        ReflectionTestUtils.setField(uploads, "shipping", blockedChannel(oldSource, gates));
        CompletableFuture<Boolean> sending = CompletableFuture.supplyAsync(() ->
                uploads.upload(old, "物流商品", number(sub, "A"), "STO", "LG-OPENID"));
        CompletableFuture<Integer> consuming = null;
        try {
            await(gates.networkEntered);
            String replacement = ship(sub, "B");
            String event = jdbc.queryForObject("SELECT event_no FROM sys_outbox WHERE aggregate_id=? "
                    + "AND event_type='SUB_ORDER_SHIPPED' ORDER BY id DESC LIMIT 1", String.class, sub);
            consuming = CompletableFuture.supplyAsync(dispatcher::dispatchPending);
            await(gates.queueRead);
            gates.networkReply.countDown();
            await(gates.finishAttempted);
            boolean completedBeforeRefresh = finishesWithinObservation(sending);
            gates.resumeRefresh.countDown();
            consuming.get(10, TimeUnit.SECONDS);
            sending.get(10, TimeUnit.SECONDS);
            assertRecovered(sub, old, oldSource, replacement, event);
            assertThat(completedBeforeRefresh).as("旧完成必须等当前来源刷新事务提交，随后丢弃旧版本").isFalse();
        } finally {
            gates.networkReply.countDown();
            gates.resumeRefresh.countDown();
            try {
                sending.get(10, TimeUnit.SECONDS);
                if (consuming != null) { consuming.get(10, TimeUnit.SECONDS); }
            } finally {
                ReflectionTestUtils.setField(uploads, "queue", actualQueue);
                ReflectionTestUtils.setField(uploads, "shipping", originalChannel);
            }
        }
    }

    private ShippingUploadQueue observedQueue(String order, Latches gates) {
        ShippingUploadQueue observed = spy(actualQueue);
        AtomicBoolean localReadFailure = new AtomicBoolean();
        AtomicBoolean paused = new AtomicBoolean();
        Answer<Object> read = invocation -> {
            Object actual = invocation.callRealMethod();
            if (localReadFailure.compareAndSet(false, true)) {
                throw new IllegalStateException("test infrastructure failure after actual local enqueue read");
            }
            if (paused.compareAndSet(false, true)) {
                gates.queueRead.countDown();
                await(gates.resumeRefresh);
            }
            return actual;
        };
        doAnswer(read).when(observed).byOrder(eq(order));
        doAnswer(read).when(observed).byOrderLocked(eq(order));
        doAnswer(invocation -> {
            gates.finishAttempted.countDown();
            return invocation.callRealMethod();
        }).when(observed).finish(any(), any(), any(), any());
        return observed;
    }

    private static WxShippingPort blockedChannel(String oldSource, Latches gates) {
        return new WxShippingPort() {
            public boolean enabled() { return true; }
            public Result upload(Command command) {
                assertThat(command.shipments().getFirst().sourceShipmentNo()).isEqualTo(oldSource);
                gates.networkEntered.countDown();
                await(gates.networkReply);
                return Result.ok();
            }
        };
    }

    private void assertRecovered(String sub, TrdShippingUpload old, String oldSource,
                                 String replacement, String event) {
        TrdShippingUpload current = row(sub);
        assertThat(current.getId()).isEqualTo(old.getId());
        assertThat(current.getSourceSnapshot()).contains(replacement).doesNotContain(oldSource);
        assertThat(current.getStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_outbox WHERE event_no=?", String.class, event))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?", Long.class, sub))
                .isEqualTo(2);
    }

    private static boolean finishesWithinObservation(CompletableFuture<Boolean> sending) throws Exception {
        try { sending.get(250, TimeUnit.MILLISECONDS); return true; }
        catch (TimeoutException expected) { return false; }
    }

    private static void await(CountDownLatch gate) {
        try { assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
    private TrdShippingUpload row(String sub) {
        return rows.selectOne(Wrappers.<TrdShippingUpload>lambdaQuery()
                .eq(TrdShippingUpload::getOrderNo, "O-" + sub).last("LIMIT 1"));
    }
    private String ship(String sub, String suffix) {
        return shipping.ship(sub, new ShipCommand(List.of(), "STO", number(sub, suffix)),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
    }
    private static String number(String sub, String suffix) {
        return "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + suffix;
    }
    private void drain() { for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); } }

    private static final class Latches {
        final CountDownLatch networkEntered = new CountDownLatch(1);
        final CountDownLatch networkReply = new CountDownLatch(1);
        final CountDownLatch queueRead = new CountDownLatch(1);
        final CountDownLatch resumeRefresh = new CountDownLatch(1);
        final CountDownLatch finishAttempted = new CountDownLatch(1);
    }
}
