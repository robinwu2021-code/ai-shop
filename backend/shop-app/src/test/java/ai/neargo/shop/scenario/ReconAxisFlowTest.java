package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.pay.service.recon.PayoutReconAxis;
import ai.neargo.shop.pay.service.recon.SplitReconAxis;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四轴对账。
 *
 * <p>这个类里最要紧的一条是<b>线下单不产生任何差异</b>：
 * 那笔钱从没进过通道，混进通道类对账就是<b>永久无解差异</b> ——
 * 没有任何人能把它处置掉。而无解差异一多，对账页就没人看了，
 * 真差异跟着一起被埋掉。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconAxisFlowTest {

    private static final String ENTITY = "M0001";

    @Autowired
    private ReconService reconService;
    @Autowired
    private SplitReconAxis splitAxis;
    @Autowired
    private PayoutReconAxis payoutAxis;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private SettleMappers.ReconDiffMapper diffMapper;

    /**
     * ⚠️ 差异表是共享的。留下几条 PENDING 会让别的用例（以及本类下一条）
     * 数出别人的差异 —— 又是「单独跑绿、全量跑红」那个形状。
     */
    @AfterEach
    void cleanDiffs() {
        DataScopeContext.executeWithoutScope(() -> diffMapper.delete(
                Wrappers.<StlReconDiff>lambdaQuery()
                        .in(StlReconDiff::getAxis, SplitReconAxis.CODE, PayoutReconAxis.CODE)));
        DataScopeContext.executeWithoutScope(() -> billMapper.delete(
                Wrappers.<StlBill>lambdaQuery().likeRight(StlBill::getSettleNo, "SB-AXIS-")));
    }

    @Test
    @DisplayName("★★★ 线下单跑遍四条轴，一条差异都不产生 —— 那笔钱从没进过通道")
    void offlineBillProducesNoDiff() {
        /*
         * ⚠️ **splitAt 要给值**，虽然真实的线下单永远没有它。
         *
         * 第一版给的是 null，用例绿了 —— 但做消融（把 OFFLINE_SETTLED 也放进查询）
         * 时它**照样绿**：`splitAt IS NULL` 被 `.le(splitAt, cutoff)` 顺带滤掉了。
         * 也就是说那条用例守的是「splitAt 为空」这个巧合，不是「按状态排除线下」。
         *
         * 给一个很久以前的 splitAt，排除那一条才真的被验到。
         */
        bill("SB-AXIS-OFF", StlBill.OFFLINE_SETTLED, hoursAgo(72), null, hoursAgo(72));

        for (var r : reconService.scanAllAxes(System.currentTimeMillis())) {
            assertThat(r.error()).as("轴 %s 跑失败", r.axis()).isNull();
        }

        assertThat(diffsOf("SB-AXIS-OFF"))
                .as("混进通道对账就是永久无解差异 —— 没人处置得了，"
                        + "而无解差异一多，真差异会跟着被埋掉")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 分账轴逮得住「发出很久没确认」，且连跑两轮不翻倍")
    void splitAxisCatchesStaleAndIsIdempotent() {
        bill("SB-AXIS-STALE", StlBill.SPLIT, hoursAgo(72), null, hoursAgo(72));

        splitAxis.scan(System.currentTimeMillis());
        assertThat(diffsOf("SB-AXIS-STALE")).hasSize(1);

        // 幂等：同一笔卡了三天，不该变成三条待处置
        splitAxis.scan(System.currentTimeMillis());
        assertThat(diffsOf("SB-AXIS-STALE")).hasSize(1);
    }

    @Test
    @DisplayName("★★ 刚发出的不算异常 —— 否则每一笔正常单都会先被报一次")
    void freshSplitIsNotADiff() {
        bill("SB-AXIS-FRESH", StlBill.SPLIT, System.currentTimeMillis(), null, System.currentTimeMillis());
        splitAxis.scan(System.currentTimeMillis());
        assertThat(diffsOf("SB-AXIS-FRESH")).isEmpty();
    }

    @Test
    @DisplayName("★★ 已确认到账的不再报 —— split_confirmed_at 一填上，这笔就出局了")
    void confirmedSplitIsNotADiff() {
        bill("SB-AXIS-DONE", StlBill.SPLIT, hoursAgo(72), hoursAgo(70), hoursAgo(72));
        splitAxis.scan(System.currentTimeMillis());
        assertThat(diffsOf("SB-AXIS-DONE")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 出款轴：同一个流水号出现在两张单上 —— 那是真金白银的重复付款")
    void payoutAxisCatchesDuplicateRef() {
        paidBill("SB-AXIS-DUP1", "BANK-9527");
        paidBill("SB-AXIS-DUP2", "BANK-9527");

        payoutAxis.scan(System.currentTimeMillis());

        // **两张都要记** —— 只记一条的话，处置的人看不出另一张是哪个
        assertThat(diffsOf("SB-AXIS-DUP1")).hasSize(1);
        assertThat(diffsOf("SB-AXIS-DUP2")).hasSize(1);
    }

    @Test
    @DisplayName("★★ 出款轴：已付款却没有流水号 —— 事后对不上是必然的")
    void payoutAxisCatchesMissingRef() {
        paidBill("SB-AXIS-NOREF", null);
        payoutAxis.scan(System.currentTimeMillis());
        assertThat(diffsOf("SB-AXIS-NOREF")).hasSize(1);
    }

    @Test
    @DisplayName("★★ 每条轴都说得清自己查不到什么 —— 不说的话「今天没有差异」是句假话")
    void everyAxisDeclaresCoverage() {
        var reports = reconService.scanAllAxes(System.currentTimeMillis());
        assertThat(reports).hasSizeGreaterThanOrEqualTo(4);
        for (var r : reports) {
            assertThat(r.coverage()).as("轴 %s 没给覆盖范围", r.axis()).isNotNull();
            assertThat(r.coverage().note())
                    .as("轴 %s 的说明是空的 —— 一条不说明覆盖范围的轴，"
                            + "它报出的「零差异」没有意义", r.axis())
                    .isNotBlank();
        }
    }

    // ── helpers ──────────────────────────────────────────────

    private static long hoursAgo(int h) {
        return System.currentTimeMillis() - h * 3_600_000L;
    }

    private List<StlReconDiff> diffsOf(String settleNo) {
        return DataScopeContext.executeWithoutScope(() ->
                diffMapper.selectList(Wrappers.<StlReconDiff>lambdaQuery()
                        .eq(StlReconDiff::getPaymentNo, settleNo)));
    }

    private void bill(String settleNo, String status, Long splitAt, Long confirmedAt, long createdAt) {
        StlBill b = new StlBill();
        b.setSettleNo(settleNo);
        b.setSubOrderNo("SUB-" + settleNo);
        b.setOrderNo("ORD-" + settleNo);
        b.setEntityNo(ENTITY);
        b.setStatus(status);
        b.setGrossMinor(10_000L);
        b.setNetMinor(9_500L);
        b.setSplitAmountMinor(500L);
        b.setSplitAt(splitAt);
        b.setSplitConfirmedAt(confirmedAt);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
    }

    private void paidBill(String settleNo, String ref) {
        StlBill b = new StlBill();
        b.setSettleNo(settleNo);
        b.setSubOrderNo("SUB-" + settleNo);
        b.setOrderNo("ORD-" + settleNo);
        b.setEntityNo(ENTITY);
        b.setStatus(StlBill.PAID);
        b.setGrossMinor(10_000L);
        b.setNetMinor(9_500L);
        b.setPaymentRef(ref);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
    }
}
