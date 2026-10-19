package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.registration.ShipmentRegistrar;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Pause after an actual source read: its child lock must cover the registrar reset until commit. */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentRegistrationConcurrencyFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private ShipmentRegistrar registrar;
    @Autowired private ShipmentSourcePort actualSource;

    @Test
    void currentIdentityLockLivesThroughOriginalEventRegistration() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, false);
        String oldSource = ship(sub, "A");
        drain();
        SysOutbox original = new SysOutbox();
        original.setPayload(jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE aggregate_id=? "
                + "AND event_type='SUB_ORDER_SHIPPED' ORDER BY id LIMIT 1", String.class, sub));
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ShipmentSourcePort blocking = blockingSource(oldSource, captured, resume);
        ReflectionTestUtils.setField(registrar, "source", blocking);
        CompletableFuture<Void> registering = CompletableFuture.runAsync(() -> registrar.consume(original));
        CompletableFuture<Void> correcting = null;
        try {
            assertThat(captured.await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch attempted = new CountDownLatch(1);
            correcting = CompletableFuture.runAsync(() -> { attempted.countDown(); ship(sub, "B"); drain(); });
            assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
            boolean finishedBeforeLockRelease;
            try { correcting.get(250, TimeUnit.MILLISECONDS); finishedBeforeLockRelease = true; }
            catch (TimeoutException expected) { finishedBeforeLockRelease = false; }
            resume.countDown();
            registering.get(10, TimeUnit.SECONDS);
            correcting.get(10, TimeUnit.SECONDS);
            assertThat(finishedBeforeLockRelease).as("当前身份校验到登记提交期间必须持有子单锁").isFalse();
            assertThat(jdbc.queryForObject("SELECT waybill_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub))
                    .isEqualTo(number(sub, "B"));
        } finally {
            resume.countDown();
            try {
                registering.get(10, TimeUnit.SECONDS);
                if (correcting != null) { correcting.get(10, TimeUnit.SECONDS); }
            } finally { ReflectionTestUtils.setField(registrar, "source", actualSource); }
        }
    }

    private ShipmentSourcePort blockingSource(String oldSource, CountDownLatch captured, CountDownLatch resume) {
        AtomicBoolean paused = new AtomicBoolean();
        return new ShipmentSourcePort() {
            public Optional<ShipmentSource> sourceOf(String no) { return actualSource.sourceOf(no); }
            public Optional<ShipmentIdentity> identityOf(String no) {
                return pause(actualSource.identityOf(no));
            }
            public Optional<ShipmentIdentity> lockedIdentityOf(String no) {
                return pause(actualSource.lockedIdentityOf(no));
            }
            private Optional<ShipmentIdentity> pause(Optional<ShipmentIdentity> read) {
                if (read.filter(s -> oldSource.equals(s.sourceShipmentNo())).isPresent()
                        && paused.compareAndSet(false, true)) {
                    captured.countDown();
                    try { assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                }
                return read;
            }
        };
    }

    private String ship(String sub, String suffix) {
        return shipping.ship(sub, new ShipCommand(List.of(), "STO", number(sub, suffix)),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
    }
    private static String number(String sub, String suffix) {
        return "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + suffix;
    }
    private void drain() { for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); } }
}
