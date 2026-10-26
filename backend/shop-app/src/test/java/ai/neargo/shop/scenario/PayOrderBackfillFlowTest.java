package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.acquiring.backfill.PayIntegrity;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 回填 B6 / B7 与完成标准 Q5 / Q7（TDD-支付服务-I3支付单退款流水 S9）。存量尝试按 I3 之前的样子落（不经支付单入口），
 * 回填后：挂上支付单、状态与实收对得上、流水期初记一次；再跑一遍什么都不多。
 */
// 支付服务 I3 的六个场景类共用一个上下文（同一个库）：各自用随机单号，互不干扰；每类一个上下文会把全量测试撑爆堆
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayOrderBackfillFlowTest {

    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private PayOrderBackfill backfill;
    @Autowired private PayIntegrity integrity;
    @Autowired private JdbcTemplate jdbc;

    private Long store() {
        return jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
    }

    /** I3 之前的样子：直接开尝试，没有支付单 */
    private String legacy(boolean paid) {
        String no = "LG-" + UUID.randomUUID().toString().substring(0, 8);
        ledger.open(new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", 1990, "goods"));
        if (paid) {
            settle.settlePayment(new PaymentSettled(no, "TEST", "TX-" + no, 1990, System.currentTimeMillis(), "CNY"));
        } else {
            ledger.close(no, "超时");
        }
        return no;
    }

    @Test
    @DisplayName("★★★ B6 已付的存量单：建支付单（SUCCEEDED、实收 1990）、尝试挂上；B7 流水期初一张；再跑一遍什么都不多")
    void paidLegacyOrderIsBackfilledOnce() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        String no = legacy(true);
        assertThat(backfill.pendingOrderNos(10_000)).contains(no);
        assertThat(integrity.unlinkedAttempts(start)).as("回填前 Q5 > 0").isPositive();

        Long id = backfill.backfill(no, PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", "AGGREGATED");
        assertThat(backfill.backfill(no, PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", "AGGREGATED")).isEqualTo(id);

        assertThat(jdbc.queryForMap("SELECT status, paid_minor, amount_minor, funds_path FROM pay_order WHERE id=?", id))
                .containsEntry("status", "SUCCEEDED").containsEntry("paid_minor", 1990L)
                .containsEntry("amount_minor", 1990L).containsEntry("funds_path", "AGGREGATED");
        assertThat(jdbc.queryForObject("SELECT pay_order_id FROM stl_payment WHERE out_trade_no=?", Long.class, no))
                .isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher WHERE idem_key=?", Integer.class,
                "PAY_SUCCEEDED|BACKFILL|" + id)).isEqualTo(1);
        assertThat(backfill.pendingOrderNos(10_000)).doesNotContain(no);
    }

    @Test
    @DisplayName("★★ 只关了的存量单：支付单 CLOSED、不记流水")
    void closedLegacyOrder() {
        String no = legacy(false);
        Long id = backfill.backfill(no, PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", "DIRECT");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE id=?", String.class, id)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_entry WHERE pay_order_id=?", Integer.class, id))
                .isZero();
    }

    @Test
    @DisplayName("★★★ 完成标准：回填后 Q5（未挂支付单的尝试）= 0、Q7（借贷不平的凭证）= 0")
    void completionChecks() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        String a = legacy(true);
        String b = legacy(false);
        backfill.backfill(a, PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", "AGGREGATED");
        backfill.backfill(b, PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", "AGGREGATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE direction='PAY' AND pay_order_id IS NULL"
                + " AND out_trade_no IN (?, ?)", Integer.class, a, b)).isZero();
        assertThat(integrity.unbalancedVouchers()).isZero();
        for (String pending : backfill.pendingOrderNos(10_000)) {
            backfill.backfill(pending, PaymentPort.MALL_ORDER, store(), "门店", "主体", "CN", "AGGREGATED");
        }
        assertThat(integrity.unlinkedAttempts(start)).as("全部回填后 Q5 = 0").isZero();
    }
}
