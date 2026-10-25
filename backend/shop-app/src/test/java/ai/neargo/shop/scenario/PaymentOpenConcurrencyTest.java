package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F07 · Review R07：并发两次「去支付」只能落一行（TDD-支付服务-I1存量缺陷）。
 *
 * <p>{@code open} 的「这单有没有未终态的收款」检查不加锁，两个请求同时过检查、各插一行同号记录；
 * 之后按单号 {@code LIMIT 1 FOR UPDATE} 锁哪一行不确定。修法是两层：{@code out_trade_no} 唯一键兜底，
 * 撞键的那一方回读已落的那行、按同一套快照比对后返回同一个单号 —— 对用户而言两次点击是同一笔。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payment-open-concurrency;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PaymentOpenConcurrencyTest {

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ai.neargo.shop.pay.channel.PaymentAccountLookup accounts;

    @RepeatedTest(20)
    @DisplayName("★★★ F07 两次并发「去支付」：只有一行，两边拿到同一个商户单号")
    void concurrentOpenYieldsOneRow() throws Exception {
        PaymentOpen cmd = command();
        int threads = 4;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return ledger.open(cmd);
            }));
        }
        start.countDown();
        List<String> outTradeNos = new ArrayList<>();
        for (var f : results) {
            outTradeNos.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND direction='PAY'", Integer.class, cmd.orderNo()))
                .as("同一订单的未终态收款只能有一行 —— 两行同号时按单号锁行锁哪行不确定").isEqualTo(1);
        assertThat(outTradeNos).as("每一次点击都该拿到同一笔").containsOnly(cmd.orderNo());
        // F08 · R08：实际收款账户落到行上，退款按它找原交易
        assertThat(jdbc.queryForMap("SELECT sub_merchant_id, pay_merchant_no FROM stl_payment WHERE out_trade_no=?",
                cmd.orderNo()))
                .containsEntry("sub_merchant_id", "synthetic-channel-account")
                .containsEntry("pay_merchant_no", "synthetic-pay");
        // F12 · R14：收付通查单要的 sub_mchid 由支付域按交易查得到
        assertThat(accounts.subMerchantOf(cmd.orderNo())).contains("synthetic-channel-account");
    }

    private static PaymentOpen command() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        var route = new PaymentRoute("synthetic-owner", "synthetic-store", "synthetic-pay", "TEST",
                "synthetic-channel-account", "synthetic-pay:7", "DIRECT");
        return new PaymentOpen("concurrent-" + id, "user-" + id, null, "TEST", 1234,
                "synthetic goods", "KWD", List.of(new PaymentStore("sub-" + id, route)));
    }
}
