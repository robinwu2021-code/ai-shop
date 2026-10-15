package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Ops child-source retry cannot acquire the parent lock while an existing parent writer waits for children. */
@SpringBootTest
@ActiveProfiles("test")
class ShippingUploadRetryConcurrencyFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private ShipmentSourcePort sources;
    @Autowired private OrderShippingService shipping;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void originalQueueRetryFinishesWhileParentLockedAndCorrectionKeepsSameQueue() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        String first = ship(sub, "A");
        drain();
        Long rowId = jdbc.queryForObject("SELECT id FROM trd_shipping_upload WHERE order_no=?",
                Long.class, "O-" + sub);
        jdbc.update("UPDATE trd_shipping_upload SET status='FAILED',err_code=9300561,err_msg='original failure' "
                + "WHERE id=?", rowId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM ord_order WHERE order_no=? FOR UPDATE",
                            Long.class, "O-" + sub);
                    locked.countDown();
                    try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> retry = CompletableFuture.runAsync(() -> sources.retryUpload(sub, first, 0));
            retry.get(3, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM trd_shipping_upload WHERE id=?", String.class, rowId))
                    .isEqualTo("PENDING");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            String replacement = ship(sub, "B");
            drain();
            assertThat(jdbc.queryForObject("SELECT id FROM trd_shipping_upload WHERE order_no=?",
                    Long.class, "O-" + sub)).isEqualTo(rowId);
            assertThat(jdbc.queryForObject("SELECT source_snapshot FROM trd_shipping_upload WHERE id=?",
                    String.class, rowId)).contains(replacement).doesNotContain(first);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private String ship(String sub, String suffix) {
        String waybill = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + suffix;
        return shipping.ship(sub, new ShipCommand(List.of(), "STO", waybill),
                new OrderActor("LG-MERCHANT", "LG-STORE")).shipmentId();
    }
    private void drain() { for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); } }
}
