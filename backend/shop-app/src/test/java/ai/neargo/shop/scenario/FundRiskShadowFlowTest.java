package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.service.AdmissionService;
import ai.neargo.shop.merchant.service.DebtService;
import ai.neargo.shop.settle.entity.StlSettleBatch;
import ai.neargo.shop.settle.risk.FundRisk;
import ai.neargo.shop.settle.risk.FundRiskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资金风控 · 影子模式。
 *
 * <p>取向（[ADR-022]）：<b>宁可漏放一笔进入追偿流程，不可无依据地冻住一笔正常货款</b>。
 * 所以这个类里最多的不是「拦住了」，而是<b>「什么情况下不该拦」</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class FundRiskShadowFlowTest {

    private static final String ENTITY = "M-RISK-T1";

    @Autowired
    private FundRiskService riskService;
    @Autowired
    private DebtService debtService;
    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("DELETE FROM pay_risk_shadow_log WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM stl_bill WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM mch_debt_txn WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM mch_debt WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM mch_deposit_txn WHERE merchant_no = ?", ENTITY);
            jdbc.update("DELETE FROM mch_deposit WHERE merchant_no = ?", ENTITY);
            return null;
        });
    }

    private StlSettleBatch batch(String no, long netMinor) {
        StlSettleBatch b = new StlSettleBatch();
        b.setBatchNo(no);
        b.setEntityNo(ENTITY);
        b.setNetMinor(netMinor);
        return b;
    }

    /** 造一笔成交（或退款）流水，用来喂退款率 */
    private void givenBill(String suffix, long gross, boolean reversed) {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO stl_bill (settle_no, sub_order_no, order_no, entity_no, pay_channel,"
                        + " status, gross_minor, net_minor, accrued_at,"
                        + " tenant_no, created_at, updated_at, version, deleted)"
                        + " VALUES (?, ?, ?, ?, 'WECHAT', ?, ?, ?, ?, 'MAIN',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                "STL-RISK-" + suffix, "SUB-RISK-" + suffix, "SO-RISK-" + suffix, ENTITY,
                reversed ? "REVERSED" : "SPLIT_CONFIRMED", gross, gross,
                System.currentTimeMillis() - 86400000L));
    }

    /**
     * 命中的规则码，<b>没有命中时返回空串而不是 null</b>。
     *
     * <p>写这个类时被绊了一下：直接对 null 调 {@code doesNotContain} 会失败，
     * 报的是「期望不为 null」—— 看起来像规则命中了，其实是<b>一条都没命中</b>。
     * 断言的失败信息把「没命中」说成了「命中了」，正好是最误导的那种。
     */
    private String hitsOf(String batchNo) {
        String v = shadowField(batchNo, "hit_rules");
        return v == null ? "" : v;
    }

    private String shadowField(String batchNo, String col) {
        return DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT " + col + " FROM pay_risk_shadow_log WHERE batch_no = ?",
                String.class, batchNo));
    }

    @Test
    @DisplayName("★★★ 影子模式永远返回 PASS —— 只记录会拦谁，不真的冻住任何一笔")
    void shadowNeverBlocks() {
        debtService.incur(ENTITY, 50000, "REFUND", "AS-RISK-1", "退款追不回");

        FundRisk.Verdict v = riskService.decide(batch("STB-RISK-1", 100000));

        assertThat(v.result()).as("影子期不拦").isEqualTo(FundRisk.PASS);
        assertThat(shadowField("STB-RISK-1", "verdict"))
                .as("但日志里要如实记 HOLD —— 复盘看的是这个")
                .isEqualTo("HOLD");
    }

    @Test
    @DisplayName("★★★ 分母为零不出结论 —— 刚开店的商家会因为一笔退款被判成 100%")
    void zeroTurnoverYieldsNoConclusion() {
        // 一笔成交都没有
        FundRisk.Verdict v = riskService.decide(batch("STB-RISK-2", 10000));

        assertThat(v.result()).isEqualTo(FundRisk.PASS);
        assertThat(shadowField("STB-RISK-2", "verdict"))
                .as("没有成交就不该有任何退款率相关的命中")
                .isEqualTo("PASS");
        assertThat(shadowField("STB-RISK-2", "refund_rate_bp"))
                .as("-1 表示不出结论，与「0%」必须分得开")
                .isEqualTo("-1");
    }

    @Test
    @DisplayName("★★★ 退款率超阈值要命中，且**说人话带数字** —— 说不清的规则申诉链路走不通")
    void refundRateHitExplainsWithNumbers() {
        givenBill("a", 10000, false);
        givenBill("b", 10000, true);      // 退了一半 → 50% > 20%

        riskService.decide(batch("STB-RISK-3", 5000));

        assertThat(hitsOf("STB-RISK-3")).contains("REFUND_RATE_HIGH");
        String explain = shadowField("STB-RISK-3", "explain_text");
        assertThat(explain)
                .as("必须含具体数字与阈值，不能是「风控审核中」")
                .contains("50.00%").contains("20.00%").contains("7 天");
    }

    @Test
    @DisplayName("★★★ 没缴保证金的不判集中度 —— 拿 0 做除数的话每一批都命中，那是全站停摆")
    void noDepositMeansNoConcentrationRule() {
        givenBill("c", 10000, false);

        riskService.decide(batch("STB-RISK-4", 99999999L));

        assertThat(hitsOf("STB-RISK-4"))
                .as("保证金为 0 时这条规则不该命中")
                .doesNotContain("BATCH_TOO_LARGE");
    }

    @Test
    @DisplayName("★★ 缴过保证金且超倍数才命中集中度")
    void concentrationHitsOnlyAboveCap() {
        givenBill("d", 10000, false);
        admissionService.recordTxn(ENTITY, "PAY", 10000, "缴纳保证金", "test");

        // 3 倍上限 = 30000
        riskService.decide(batch("STB-RISK-5", 20000));
        assertThat(hitsOf("STB-RISK-5"))
                .as("2 万没超 3 万，不该命中")
                .doesNotContain("BATCH_TOO_LARGE");

        riskService.decide(batch("STB-RISK-6", 40000));
        assertThat(hitsOf("STB-RISK-6")).contains("BATCH_TOO_LARGE");
    }

    @Test
    @DisplayName("★★ 欠款的话术是「会扣除后放款」不是「转人工」—— 后者会让商家以为钱冻住了")
    void debtExplainSaysDeductNotHold() {
        debtService.incur(ENTITY, 12800, "REFUND", "AS-RISK-2", "退款追不回");

        riskService.decide(batch("STB-RISK-7", 50000));

        assertThat(shadowField("STB-RISK-7", "explain_text"))
                .contains("128.00").contains("扣除后放款");
    }

    @Test
    @DisplayName("★★★ 一批只记一条 —— 重复记会把「命中率」这个复盘判据撑大")
    void shadowLogIsIdempotentPerBatch() {
        debtService.incur(ENTITY, 5000, "REFUND", "AS-RISK-3", "退款追不回");

        riskService.decide(batch("STB-RISK-8", 10000));
        riskService.decide(batch("STB-RISK-8", 10000));
        riskService.decide(batch("STB-RISK-8", 10000));

        Integer rows = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_risk_shadow_log WHERE batch_no = ?",
                Integer.class, "STB-RISK-8"));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ PASS 的批次不记「本可拦下多少」—— 记了的话复盘总额里混着不会被拦的钱")
    void passBatchHoldsNothing() {
        givenBill("e", 10000, false);

        riskService.decide(batch("STB-RISK-9", 5000));

        assertThat(shadowField("STB-RISK-9", "would_hold_minor")).isEqualTo("0");
    }
}
