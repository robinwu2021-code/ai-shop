package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 端每日流水（TDD-供应商结算与双轨资金 §3.3 · P1b）。
 *
 * <p>这一页答的是<b>「我哪天赚了多少」</b>，而既有的收入总览答的是「钱在哪一档」。
 * 两者在同一屏上，所以<b>最要紧的一条是它们对得上</b> ——
 * 商家看到「四个数加起来 ≠ 每日流水加起来」时，不会去想是哪个口径的问题，
 * 只会认为平台算错了他的钱。最后一个用例钉的就是这件事。
 *
 * <p>只要 test profile：用的全是没有 @Profile 限制的 bean。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizDailyFlowFlowTest {

    private static final String ENT = "E-DF-1";

    @Autowired
    private SettleService settleService;
    @Autowired
    private BillMapper bills;

    private String run;
    private final ZoneId zone = ZoneId.systemDefault();
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void seed() {
        run = Long.toString(System.nanoTime());
    }

    @AfterEach
    void cleanup() {
        bills.delete(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getEntityNo, ENT));
    }

    @Test
    @DisplayName("按成交日分组；没有流水的那天不占一行")
    void groupsByAccruedDayAndSkipsEmptyDays() {
        bill("a", today, 10_000, StlBill.PENDING, null);
        bill("b", today, 20_000, StlBill.PENDING, null);
        bill("c", today.minusDays(3), 5_000, StlBill.PENDING, null);

        var page = flows(today.minusDays(7), today);

        // 三张单落在两天里 —— 中间那五天没有流水，不该补零占行
        assertThat(page.days()).hasSize(2);
        assertThat(page.days().get(0).day()).isEqualTo(today.toString());   // 最近的在前
        assertThat(page.days().get(0).grossMinor()).isEqualTo(30_000);
        assertThat(page.days().get(0).billCount()).isEqualTo(2);
        assertThat(page.days().get(1).day()).isEqualTo(today.minusDays(3).toString());
    }

    @Test
    @DisplayName("★ 退款回退只计 refund，不冲减当天的成交额与净额")
    void reversedGoesToRefundOnly() {
        bill("ok", today, 10_000, StlBill.PENDING, null);
        bill("rv", today, 3_000, StlBill.REVERSED, null);

        var day = flows(today, today).days().get(0);

        // 被退的那笔在它自己成交的那天已经记过，这里再减一次就是记两遍
        assertThat(day.grossMinor()).isEqualTo(10_000);
        assertThat(day.netMinor()).isEqualTo(10_000);
        assertThat(day.refundMinor()).isEqualTo(3_000);   // 正数，方向靠这一列的名字
        assertThat(day.billCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 没有成交日的存量单不会凭空消失 —— 单独回一个合计让页面说出来")
    void undatedBillsAreReportedNotDropped() {
        bill("dated", today, 10_000, StlBill.PENDING, null);
        undatedBill("old", 7_000);

        var page = flows(today.minusDays(30), today);

        assertThat(page.days()).hasSize(1);
        assertThat(page.days().get(0).netMinor()).isEqualTo(10_000);
        assertThat(page.undatedCount()).isEqualTo(1);
        assertThat(page.undatedMinor()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("区间之外的不进来，边界那两天要在里面")
    void rangeIsInclusiveOnBothEnds() {
        bill("in1", today.minusDays(5), 1_000, StlBill.PENDING, null);
        bill("in2", today, 2_000, StlBill.PENDING, null);
        bill("out", today.minusDays(6), 9_000, StlBill.PENDING, null);

        var page = flows(today.minusDays(5), today);

        assertThat(page.days()).hasSize(2);
        assertThat(page.days().stream().mapToLong(SettleService.DailyFlowVO::netMinor).sum())
                .isEqualTo(3_000);
    }

    @Test
    @DisplayName("门店收窄：没有门店归属的存量行照样放行 —— 与结算明细同一条规矩")
    void storeScopeLetsUnassignedRowsThrough() {
        bill("s1", today, 1_000, StlBill.PENDING, "ST-" + run + "-A");
        bill("s2", today, 2_000, StlBill.PENDING, "ST-" + run + "-B");
        bill("none", today, 4_000, StlBill.PENDING, null);   // 无门店归属

        var scoped = settleService.dailyFlows(ENT, List.of("ST-" + run + "-A"),
                today.toString(), today.toString());

        // A 店那笔 + 无归属那笔。**无归属的不放行的话，开了两家店的商家会看到页面突然变空**
        assertThat(scoped.days().get(0).netMinor()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("★★ 每日流水加起来 = 收入总览四档加起来 —— 两处必须同源")
    void dailyFlowsAgreeWithIncomeSummary() {
        bill("p", today, 10_000, StlBill.PENDING, null);
        bill("s", today.minusDays(1), 20_000, StlBill.SPLIT, null);
        bill("c", today.minusDays(2), 30_000, StlBill.SPLIT_CONFIRMED, null);
        bill("o", today.minusDays(3), 40_000, StlBill.OFFLINE_SETTLED, null);
        bill("rv", today.minusDays(4), 5_000, StlBill.REVERSED, null);

        var sum = settleService.incomeSummary(ENT, List.of());
        long fourBuckets = sum.receivedMinor() + sum.inFlightMinor()
                + sum.pendingMinor() + sum.offlineMinor();

        var page = flows(today.minusDays(30), today);
        long flowNet = page.days().stream()
                .mapToLong(SettleService.DailyFlowVO::netMinor).sum() + page.undatedMinor();

        /*
         * 两边都**不含退款回退**：四档里 REVERSED 被显式排除，
         * 每日流水里它只进 refund 那一列。所以这两个数必须逐分相等 ——
         * 不等就说明有一侧的口径动过，而页面上那会表现成「平台算错了我的钱」。
         */
        assertThat(flowNet).isEqualTo(fourBuckets).isEqualTo(100_000);
        // 退的那笔确实被记在了 refund 上，不是被丢掉了
        assertThat(page.days().stream()
                .mapToLong(SettleService.DailyFlowVO::refundMinor).sum()).isEqualTo(5_000);
    }

    private SettleService.DailyFlowPageVO flows(LocalDate from, LocalDate to) {
        return settleService.dailyFlows(ENT, List.of(), from.toString(), to.toString());
    }

    private void bill(String tag, LocalDate accruedDay, long net, String status, String storeNo) {
        // 落在当天正午：避开时区边界上「零点整那一笔算哪天」的干扰
        insert(tag, accruedDay.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                net, status, storeNo);
    }

    private void undatedBill(String tag, long net) {
        insert(tag, null, net, StlBill.PENDING, null);
    }

    private void insert(String tag, Long accruedAt, long net, String status, String storeNo) {
        StlBill b = new StlBill();
        String no = "SB-DF-" + run + "-" + tag;
        b.setSettleNo(no);
        b.setSubOrderNo("SUB-" + no);
        b.setOrderNo("SO-" + no);
        b.setEntityNo(ENT);
        b.setStoreNo(storeNo);
        b.setGrossMinor(net);
        b.setCommissionMinor(0L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(net);
        b.setStatus(status);
        b.setAccruedAt(accruedAt);
        b.setBusinessMode(MerchantQueryPort.MODE_SELF_OPERATED);
        b.setTenantNo("MAIN");
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        bills.insert(b);
    }
}
