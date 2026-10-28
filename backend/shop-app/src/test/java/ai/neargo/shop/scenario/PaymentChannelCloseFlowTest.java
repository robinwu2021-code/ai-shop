package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.PayerIdentity;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.port.FakePayQueryPort;
import ai.neargo.shop.trade.service.OrderService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 数据库、应用服务和真实测试银行一起证明：关闭后不能付款，延迟发出的下单也不能重新开户。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-channel-close;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PaymentChannelCloseFlowTest {
    @Autowired private PaymentPort payments;
    @Autowired private OrderService orders;
    @Autowired private SettlePort settlement;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrdinaryPaymentInitiationService initiation;
    @Autowired private TestPayGateway bank;
    @Autowired private FakePayQueryPort query;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private LegacyChannelBridge channelBridge;

    @AfterEach
    void reset() { query.reset(); }

    private SettlePort.PaymentOpen opened() {
        String no = "CLOSE-" + UUID.randomUUID();
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        payments.createPayment(new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store, "store", "entity",
                "CN", 1990, "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED"));
        SettlePort.PaymentOpen request = new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", 1990, "goods");
        ledger.open(request);
        return request;
    }

    @Test
    void channelAndLocalOrderAreBothClosed() {
        var request = opened();
        bank.placeOrder(request.orderNo(), 1990);
        query.answer(new PayQueryPort.Result(true, false, true, 1990, null));
        assertThat(payments.closePayment(PaymentPort.MALL_ORDER, request.orderNo(), "expired").status())
                .isEqualTo("CLOSED");
        assertThat(bank.markPaid(request.orderNo())).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM stl_payment WHERE out_trade_no=?", String.class,
                request.orderNo())).isEqualTo("CLOSED");
    }

    @Test
    void closeWhilePrepayIsDelayedPreventsLateChannelCreation() throws Exception {
        var request = opened();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("prepay gate timeout"); }
            return inv.callRealMethod();
        }).when(channelBridge).prepay(anyString(), any());
        var sender = Executors.newSingleThreadExecutor();
        try {
            var initiated = sender.submit(() -> initiation.initiate(request,
                    new PayerIdentity(request.userNo(), request.orderNo(), null, null,
                            PayOrdinaryPaymentRequests.hash(request))));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            query.answer(new PayQueryPort.Result(true, false, false, 0, null));
            assertThat(payments.closePayment(PaymentPort.MALL_ORDER, request.orderNo(), "expired").status())
                    .isEqualTo("CLOSED");
            release.countDown();
            assertThat(initiated.get(10, TimeUnit.SECONDS).success()).isFalse();
            assertThat(bank.markPaid(request.orderNo())).isFalse();
        } finally {
            release.countDown();
            sender.shutdownNow();
        }
    }
    @Test
    void cancelledBusinessOrderWithUnknownChannelRemainsAnExceptionOnLatePayment() {
        var request = opened();
        String no = request.orderNo();
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,pay_amount,currency,tenant_no,created_at,"
                + "updated_at,version,deleted,pay_deadline_at) VALUES (?,?,'WAIT_PAY',1990,'CNY','MAIN',"
                + "NOW(),NOW(),0,0,1)", no, request.userNo());
        query.answer(new PayQueryPort.Result(false, false, false, 0, null));
        orders.closeUnpaid(no, "expired");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, no))
                .isEqualTo("CLOSED");
        settlement.settlePayment(new SettlePort.PaymentSettled(no, "TEST", "TX-LATE-" + no, 1990,
                System.currentTimeMillis(), "CNY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_exception WHERE stl_payment_id="
                + "(SELECT id FROM stl_payment WHERE out_trade_no=?) AND kind='LATE_PAYMENT'", Long.class, no))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE out_order_no=?", String.class, no))
                .isNotEqualTo("SUCCEEDED");
        settlement.settlePayment(new SettlePort.PaymentSettled(no, "TEST", "TX-LATE-" + no, 1990,
                System.currentTimeMillis(), "CNY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_exception WHERE stl_payment_id="
                + "(SELECT id FROM stl_payment WHERE out_trade_no=?) AND kind='LATE_PAYMENT'", Long.class, no))
                .isEqualTo(1);
    }

}
