package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.ledger.LedgerAccount;
import ai.neargo.shop.pay.ledger.LedgerVoucher;
import ai.neargo.shop.pay.ledger.service.LedgerService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC5：每笔支付成功一张借贷平衡的凭证、重复事件不重复记、按资金路径分账户（TDD-支付服务-I3支付单退款流水 S5）。
 * 完成标准 Q7（借贷不平的凭证数）= 0。
 */
// 支付服务 I3 的六个场景类共用一个上下文（同一个库）：各自用随机单号，互不干扰；每类一个上下文会把全量测试撑爆堆
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class LedgerFlowTest {

    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LedgerService ledgerService;

    private String paid(String fundsPath, long amount) {
        String no = "PO-LG-" + UUID.randomUUID().toString().substring(0, 8);
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        payments.createPayment(new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store, "门店", "主体", "CN",
                amount, "CNY", "goods", System.currentTimeMillis() + 900_000, fundsPath));
        ledger.open(new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", amount, "goods"));
        var receipt = new PaymentSettled(no, "TEST", "TX-" + no, amount, System.currentTimeMillis(), "CNY");
        settle.settlePayment(receipt);
        settle.settlePayment(receipt);
        return no;
    }

    private List<Map<String, Object>> entries(String no) {
        return jdbc.queryForList("SELECT e.account, e.direction, e.amount_minor, v.voucher_type, v.biz_date"
                + " FROM pay_ledger_entry e JOIN pay_ledger_voucher v ON v.id = e.pay_ledger_voucher_id"
                + " JOIN pay_order o ON o.id = e.pay_order_id WHERE o.out_order_no=? ORDER BY e.line_no", no);
    }

    @Test
    @DisplayName("★★★ AGGREGATED 支付成功（回执两次）：一张凭证，借平台渠道余额、贷应付商户")
    void aggregatedPaymentBooksOnce() {
        String no = paid("AGGREGATED", 1990);
        assertThat(entries(no)).extracting(e -> e.get("account") + " " + e.get("direction") + " " + e.get("amount_minor"))
                .containsExactly("PLATFORM_CHANNEL DR 1990", "MERCHANT_PAYABLE CR 1990");
        assertThat(entries(no)).allSatisfy(e -> assertThat(e).containsEntry("voucher_type", "PAY_SUCCEEDED"));
        assertThat(((java.sql.Date) entries(no).get(0).get("biz_date")).toLocalDate())
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    @DisplayName("★★★ DIRECT 支付成功：只记平台外备查账，不碰平台余额")
    void directPaymentIsOffPlatform() {
        String no = paid("DIRECT", 500);
        assertThat(entries(no)).extracting(e -> e.get("account") + " " + e.get("direction"))
                .containsExactly("MERCHANT_CHANNEL DR", "MERCHANT_SALES CR");
    }

    @Test
    @DisplayName("★★★ Q7：借贷不平的凭证 = 0；凭证合计 = 借方合计")
    void everyVoucherBalances() {
        paid("AGGREGATED", 300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher v WHERE v.total_minor <>"
                + " (SELECT COALESCE(SUM(amount_minor),0) FROM pay_ledger_entry WHERE pay_ledger_voucher_id=v.id AND direction='DR')"
                + " OR v.total_minor <>"
                + " (SELECT COALESCE(SUM(amount_minor),0) FROM pay_ledger_entry WHERE pay_ledger_voucher_id=v.id AND direction='CR')",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("★★★ 同一资金事件（同一幂等键）记两次：一张凭证；冲正追加一张反向凭证、原凭证不动")
    void postIsIdempotentAndReversalAppends() {
        String key = "TEST|IDEM|" + UUID.randomUUID();
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        var v = new LedgerVoucher("ADJUST", "MANUAL", null, key, "CN", "CNY", LocalDate.now(), "AGGREGATED", null, null,
                "test", List.of(new LedgerVoucher.Line(LedgerAccount.PLATFORM_CHANNEL, LedgerVoucher.Direction.DR, 7,
                        store, null, "TEST", null), new LedgerVoucher.Line(LedgerAccount.MERCHANT_PAYABLE,
                        LedgerVoucher.Direction.CR, 7, store, null, "TEST", null)));
        Long first = ledgerService.post(v);
        assertThat(ledgerService.post(v)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ledger_voucher WHERE idem_key=?", Integer.class, key))
                .isEqualTo(1);
        Long reversal = ledgerService.reverse(first, "测试冲正");
        assertThat(jdbc.queryForMap("SELECT voucher_type, reversal_of_id FROM pay_ledger_voucher WHERE id=?", reversal))
                .containsEntry("voucher_type", "REVERSAL").containsEntry("reversal_of_id", first);
        assertThat(jdbc.queryForList("SELECT account, direction FROM pay_ledger_entry WHERE pay_ledger_voucher_id=?"
                + " ORDER BY line_no", reversal)).extracting(e -> e.get("account") + " " + e.get("direction"))
                .containsExactly("PLATFORM_CHANNEL CR", "MERCHANT_PAYABLE DR");
    }
}
