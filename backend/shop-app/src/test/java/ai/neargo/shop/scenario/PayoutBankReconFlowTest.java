package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.entity.StlBankFlow;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayout;
import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.mapper.SettleMappers.BankFlowMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.ReconDiffMapper;
import ai.neargo.shop.pay.service.recon.PayoutReconAxis;
import ai.neargo.shop.pay.service.recon.ReconAxis;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出款对账的 B 侧（ADR-011 · TDD §3.4 · P3）。
 *
 * <p><b>这条轴最容易犯的错不是漏报，是误报</b>：某天的银行流水还没上传时，
 * 那天的付款单在比对里一条都找不到 —— 记成差异等于把一批真付过的款
 * 报成「银行没划出去」，而运营会真的拿着它去查银行。
 * 所以第一条用例钉的就是「没有流水时一条差异都不许记」。
 *
 * <p>只要 test profile：用的全是没有 @Profile 限制的 bean。
 * 多一个 context key 会让 sql-init 重放种子撞主键（见 schema-test.sql 头部）。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayoutBankReconFlowTest {

    private static final String ENT = "E-BRK-1";

    @Autowired
    private PayoutReconAxis axis;
    @Autowired
    private BillMapper bills;
    @Autowired
    private BankFlowMapper flows;
    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.PayoutMapper payouts;
    @Autowired
    private ReconDiffMapper diffs;

    private String run;
    /** 付款日：固定用「今天」，因为流水的覆盖区间也按它造 */
    private final String today = LocalDate.now().toString();
    private final long todayMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

    @BeforeEach
    void seed() {
        run = Long.toString(System.nanoTime());
    }

    @AfterEach
    void cleanup() {
        bills.delete(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getEntityNo, ENT));
        payouts.delete(Wrappers.<StlPayout>lambdaQuery().eq(StlPayout::getEntityNo, ENT));
        diffs.delete(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getAxis, PayoutReconAxis.CODE)
                .likeRight(StlReconDiff::getPaymentNo, "PO-BRK-" + run));
        flows.delete(Wrappers.<StlBankFlow>lambdaQuery().likeRight(StlBankFlow::getFlowNo, "BF-" + run));
        diffs.delete(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getAxis, PayoutReconAxis.CODE)
                .likeRight(StlReconDiff::getPaymentNo, "SB-BRK-" + run));
        diffs.delete(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getAxis, PayoutReconAxis.CODE)
                .likeRight(StlReconDiff::getChannelTxnNo, "BF-" + run));
    }

    @Test
    @DisplayName("★ 一条银行流水都没有时，一条差异都不记 —— 全部留到下一轮")
    void noBankFlowMeansDeferredNotDiff() {
        paidBill("a", "BF-" + run + "-a", 10_000);
        paidBill("b", "BF-" + run + "-b", 20_000);

        ReconAxis.ScanOutcome out = axis.scan(System.currentTimeMillis());

        // 没有外部判据就不下结论：这两张单既没被判「银行没划」，也没被当成对上了
        assertThat(myDiffs(PayoutReconAxis.CODE)).isEmpty();
        assertThat(out.deferred()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("流水覆盖到的日期里找不到对应流水 —— 这才是差异")
    void missingFlowInsideCoveredRangeIsADiff() {
        paidBill("a", "BF-" + run + "-missing", 10_000);
        // 同一天有别的流水 → 这一天被「覆盖」了，所以找不到就是真差异
        bankFlow("BF-" + run + "-other", today, 99_000);

        axis.scan(System.currentTimeMillis());

        assertThat(myDiffs(PayoutReconAxis.CODE))
                .anyMatch(d -> "PAYOUT_NO_BANK_FLOW".equals(d.getDiffType())
                        && ("SB-BRK-" + run + "-a").equals(d.getPaymentNo()));
    }

    @Test
    @DisplayName("★ 银行划了而系统没登记 —— 单独一类差异，判据标为 BANK_FLOW")
    void bankOnlyFlowIsItsOwnDiff() {
        bankFlow("BF-" + run + "-orphan", today, 55_000);

        axis.scan(System.currentTimeMillis());

        StlReconDiff d = myDiffs(PayoutReconAxis.CODE).stream()
                .filter(x -> "PAYOUT_BANK_UNMATCHED".equals(x.getDiffType()))
                .findFirst().orElseThrow();
        // 金额记在「通道侧」那一列：这个数是银行给的，不是我方算的
        assertThat(d.getChannelAmountMinor()).isEqualTo(55_000);
        // 判据来源要能分辨 —— 处置的人得知道这条是谁说的
        assertThat(d.getSource()).isEqualTo("BANK_FLOW");
    }

    @Test
    @DisplayName("勾上的把关系落到流水上，不用下次再算一遍")
    void matchedFlowRecordsTheSettleNo() {
        String ref = "BF-" + run + "-hit";
        paidBill("h", ref, 30_000);
        bankFlow(ref, today, 30_000);

        axis.scan(System.currentTimeMillis());

        StlBankFlow f = flows.selectOne(Wrappers.<StlBankFlow>lambdaQuery()
                .eq(StlBankFlow::getFlowNo, ref));
        assertThat(f.getMatchedSettleNo()).isEqualTo("SB-BRK-" + run + "-h");
        // 勾上了就不该再报这两类差异中的任何一类
        assertThat(myDiffs(PayoutReconAxis.CODE)).isEmpty();
    }

    // ==================== 放款粒度（V391 / TDD-账期推进与放款记录 AC7） ====================

    @Test
    @DisplayName("★★★ 放款记录与流水勾上 → MATCHED、流水记 matched_payout_no —— 这一步只能由对账轴写")
    void payoutMatchedByBankFlowBecomesMatched() {
        String ref = "BF-" + run + "-po";
        String payoutNo = paidPayout("m", ref, 30_000);
        bankFlow(ref, today, 30_000);

        axis.scan(System.currentTimeMillis());

        StlPayout p = payouts.selectOne(Wrappers.<StlPayout>lambdaQuery().eq(StlPayout::getPayoutNo, payoutNo));
        assertThat(p.getStatus()).isEqualTo(StlPayout.MATCHED);
        assertThat(p.getBankFlowNo()).isEqualTo(ref);
        assertThat(p.getMatchedAt()).isNotNull();
        StlBankFlow f = flows.selectOne(Wrappers.<StlBankFlow>lambdaQuery().eq(StlBankFlow::getFlowNo, ref));
        assertThat(f.getMatchedPayoutNo()).isEqualTo(payoutNo);
        assertThat(myDiffs(PayoutReconAxis.CODE)).isEmpty();
    }

    @Test
    @DisplayName("★★★ 同一个凭证号登在两笔放款上 —— 一笔钱记成两笔付出，每笔各记一条 DUP_REF")
    void duplicateRefAcrossPayoutsIsADiffOnEach() {
        String ref = "BF-" + run + "-dup";
        String a = paidPayout("d1", ref, 10_000);
        String b = paidPayout("d2", ref, 20_000);

        axis.scan(System.currentTimeMillis());

        assertThat(myDiffs(PayoutReconAxis.CODE))
                .filteredOn(d -> "PAYOUT_DUP_REF".equals(d.getDiffType()))
                .extracting(StlReconDiff::getPaymentNo)
                .containsExactlyInAnyOrder(a, b);
    }

    /** 一笔已登记凭证的放款记录（直接造，不走批次链：这里验的是对账，不是放款） */
    private String paidPayout(String tag, String ref, long amount) {
        StlPayout p = new StlPayout();
        p.setPayoutNo("PO-BRK-" + run + "-" + tag);
        p.setBatchNo("STB-BRK-" + run);
        p.setEntityNo(ENT);
        p.setAmountMinor(amount);
        p.setBillCount(1);
        p.setCurrency("CNY");
        p.setStatus(StlPayout.PAID);
        p.setChannel(StlPayout.CHANNEL_MANUAL);
        p.setPaymentRef(ref);
        p.setPaidAt(todayMillis);
        p.setTenantNo("MAIN");
        p.setCreatedAt(java.time.LocalDateTime.now());
        p.setUpdatedAt(java.time.LocalDateTime.now());
        payouts.insert(p);
        return p.getPayoutNo();
    }

    @Test
    @DisplayName("★ 覆盖范围那句话跟着数据变，不是写死的")
    void coverageNoteFollowsTheData() {
        String before = axis.coverage().note();
        assertThat(before).contains("还没有导入过银行流水");

        bankFlow("BF-" + run + "-cov", today, 1_000);

        String after = axis.coverage().note();
        assertThat(after).contains(today).contains("已覆盖");
        // **仍然不是 complete**：人工上传天然不完整，说成完整会让「这轴为空」被读成「全对上了」
        assertThat(axis.coverage().complete()).isFalse();
    }

    @Test
    @DisplayName("★ 付款日在流水覆盖区间之外 —— 跳过，不当成差异")
    void outsideCoveredRangeIsDeferredNotDiff() {
        // 流水只覆盖「今天」
        bankFlow("BF-" + run + "-today", today, 1_000);
        // 而这张单付于十天前 —— 那一天的流水压根没传过
        paidBill("old", "BF-" + run + "-old", 10_000, todayMillis - 10L * 86_400_000L);

        ReconAxis.ScanOutcome out = axis.scan(System.currentTimeMillis());

        /*
         * **它不该被判成「银行没划出去」。** 我方没有那天的流水，
         * 所以这件事现在判不了 —— 判不了就留着，不是报差异。
         */
        assertThat(myDiffs(PayoutReconAxis.CODE))
                .noneMatch(d -> ("SB-BRK-" + run + "-old").equals(d.getPaymentNo()));
        assertThat(out.deferred()).isGreaterThanOrEqualTo(1);
    }

    private List<StlReconDiff> myDiffs(String code) {
        return diffs.selectList(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getAxis, code)
                .and(w -> w.likeRight(StlReconDiff::getPaymentNo, "SB-BRK-" + run)
                        .or().likeRight(StlReconDiff::getChannelTxnNo, "BF-" + run)));
    }

    private void paidBill(String tag, String paymentRef, long net) {
        paidBill(tag, paymentRef, net, todayMillis);
    }

    private void paidBill(String tag, String paymentRef, long net, long paidAt) {
        StlBill b = new StlBill();
        String no = "SB-BRK-" + run + "-" + tag;
        b.setSettleNo(no);
        b.setSubOrderNo("SUB-" + no);
        b.setOrderNo("SO-" + no);
        b.setEntityNo(ENT);
        b.setGrossMinor(net);
        b.setCommissionMinor(0L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(net);
        b.setStatus(StlBill.PAID);
        b.setPaymentRef(paymentRef);
        b.setPaidAt(paidAt);
        b.setBusinessMode(MerchantQueryPort.MODE_SELF_OPERATED);
        b.setTenantNo("MAIN");
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        bills.insert(b);
    }

    private void bankFlow(String flowNo, String tradeDate, long amount) {
        StlBankFlow f = new StlBankFlow();
        f.setFlowNo(flowNo);
        f.setTradeDate(tradeDate);
        f.setDirection(StlBankFlow.OUT);
        f.setAmountMinor(amount);
        f.setCounterpartyName("测试供应商");
        f.setImportedBy("ST-TEST");
        f.setImportedAt(Instant.now().toEpochMilli());
        f.setTenantNo("MAIN");
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        flows.insert(f);
    }
}
