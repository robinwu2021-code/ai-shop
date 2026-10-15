package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.trade.SettleSourcePort.SettleSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SettlementRefundSerializationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettleService service;
    @Autowired private PaymentLedgerService ledger;

    @Test
    void concurrentFirstGenerationAndCashReceiptDeductExactlyOnce() throws Exception {
        var fixture = new SettlementBasisFixtures(jdbc, service);
        String no = fixture.payment();
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,sub_order_no,after_sale_no,user_no,"
                + "pay_channel,pay_scene,amount_minor,currency,status,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'REFUND',?,?,?,'SERIAL-BUYER','WECHAT','MP_WECHAT',100,'CNY','PENDING','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", "RF-" + no, no, no, "AS-" + no);
        var src = new SettleSource(no, "M0001", "PLATFORM", 300, 0, 0, null, 1,
                "ST-M0001", 0, 0, "WECHAT", "MP_WECHAT", 0, 0, "MERCHANT_SELF");
        var input = new SettleService.SettleInput(src, "THIRD_PARTY", "ORIGINAL-MCH", "ENTERPRISE",
                "DIRECT", "PLATFORM", "CN");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var generation = pool.submit(() -> {
                await(ready, start); return service.generateForOrder(no, List.of(input));
            });
            var receipt = pool.submit(() -> {
                await(ready, start); ledger.markRefundSettled("RF-" + no, "CFM-" + no);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(generation.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            receipt.get(20, TimeUnit.SECONDS);
        }
        assertThat(fixture.bill(no)).containsEntry("REFUNDED_GOODS_MINOR", 100L);
        long original = (Long) fixture.basis(no).get("MERCHANT_GOODS_MINOR");
        assertThat(fixture.bill(no)).containsEntry("NET_MINOR", original - original / 3);
        assertThat(fixture.count("stl_bill_refund", no)).isEqualTo(1);
        ledger.markRefundSettled("RF-" + no, "REPLAY-" + no);
        assertThat(fixture.count("stl_bill_refund", no)).isEqualTo(1);
        assertThat(fixture.bill(no)).containsEntry("NET_MINOR", original - original / 3);
    }

    private static void await(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
