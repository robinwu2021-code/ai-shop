package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.capability.WaybillTokenBinder;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.logistics.wxbind.WxBindExecutor;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Real stores must isolate queries and token responses that were already in flight before correction. */
@SpringBootTest
@ActiveProfiles("test")
class OrderLogisticsLateChannelFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private ChannelRouter router;
    @Autowired private LogisticsPort logistics;
    @Autowired private WxBindExecutor binder;

    @Test
    void lateSignedQueryCannotPolluteReplacementFactsOrReturnedTrace() throws Exception {
        String sub = ready("A");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        StatusProbe fake = new StatusProbe() {
            public String channel() { return "wx"; }
            public boolean coversAllCarriers() { return true; }
            public Optional<TraceResult> probe(ProbeCmd command) {
                assertThat(command.waybillNo()).isEqualTo(number(sub, "A"));
                await(entered, released);
                return Optional.of(new TraceResult(number(sub, "A"), "STO", TraceStatus.SIGNED, "wx",
                        List.of(new TraceResult.TraceNode(System.currentTimeMillis(), TraceStatus.SIGNED,
                                "旧运单渠道签收节点", "杭州"))));
            }
        };
        StatusProbe original = router.installedProbes().put("wx", fake);
        CompletableFuture<LogisticsPort.TrackView> query = CompletableFuture.supplyAsync(() ->
                logistics.track(LogisticsPort.TrackQuery.subOrder(sub, "MP", false)).orElseThrow());
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            ship(sub, "B");
            drain();
            released.countDown();
            LogisticsPort.TrackView view = query.get(10, TimeUnit.SECONDS);
            assertThat(view.waybillNo()).isEqualTo(number(sub, "B"));
            assertThat(view.status()).isEqualTo("CREATED");
            assertThat(view.nodes()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT signed_at FROM lgs_waybill WHERE biz_ref=?",
                    Long.class, sub)).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM ord_shipment WHERE sub_order_no=? AND status<>'VOIDED'",
                    String.class, sub)).isEqualTo("CREATED");
        } finally {
            released.countDown();
            query.get(10, TimeUnit.SECONDS);
            router.installedProbes().put("wx", original);
        }
    }

    @Test
    void lateOldTokenCannotAttachToReplacement() throws Exception {
        String sub = ready("C");
        jdbc.update("UPDATE lgs_waybill SET display_token=NULL,bind_state='WAITING',status='PICKED_UP',"
                + "wx_uploaded_at=? WHERE biz_ref=?", System.currentTimeMillis(), sub);
        String lgsNo = jdbc.queryForObject("SELECT shipment_no FROM lgs_waybill WHERE biz_ref=?", String.class, sub);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        WaybillTokenBinder fake = new WaybillTokenBinder() {
            public String channel() { return "wx"; }
            public ChannelOutcome bind(BindCmd command) {
                assertThat(command.waybillNo()).isEqualTo(number(sub, "C"));
                assertThat(command.openid()).isEqualTo("LG-OPENID");
                await(entered, released);
                return ChannelOutcome.ok("0", "OLD-WAYBILL-TOKEN");
            }
        };
        Object original = ReflectionTestUtils.getField(binder, "binders");
        ReflectionTestUtils.setField(binder, "binders", List.of(fake));
        SysOutbox event = new SysOutbox();
        event.setPayload("{\"shipmentNo\":\"" + lgsNo + "\"}");
        CompletableFuture<Void> binding = CompletableFuture.runAsync(() -> binder.consume(event));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            ship(sub, "D");
            drain();
            released.countDown();
            binding.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT display_token FROM lgs_waybill WHERE biz_ref=?",
                    String.class, sub)).isNull();
            assertThat(jdbc.queryForObject("SELECT bind_state FROM lgs_waybill WHERE biz_ref=?",
                    String.class, sub)).isNotEqualTo("DONE");
        } finally {
            released.countDown();
            binding.get(10, TimeUnit.SECONDS);
            ReflectionTestUtils.setField(binder, "binders", original);
        }
    }

    private String ready(String suffix) {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        ship(sub, suffix);
        drain();
        jdbc.update("UPDATE lgs_waybill SET profile='WX',display_token='LOCAL-TOKEN',wx_status_checked_at=NULL "
                + "WHERE biz_ref=?", sub);
        return sub;
    }
    private void ship(String sub, String suffix) {
        shipping.ship(sub, new ShipCommand(List.of(), "STO", number(sub, suffix)),
                new OrderActor("LG-MERCHANT", "LG-STORE"));
    }
    private static String number(String sub, String suffix) {
        return "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + suffix;
    }
    private static void await(CountDownLatch entered, CountDownLatch released) {
        entered.countDown();
        try {
            if (!released.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("release timeout"); }
        } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
    private void drain() {
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
    }
}
