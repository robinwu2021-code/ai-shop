package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.settle.StubSplitGateway;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlSplitLog;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分补差（落地清单 P2-8）。
 *
 * <p>修的是一个<b>正在发生的错</b>：{@code ord_sub_order.points_deduct} 的注释写着
 * 「平台内部字段，不下发商家端 —— 商家按订单全额收款」，而实际链路是
 * {@code pay_amount} 已经把积分扣掉、结算又拿 {@code pay_amount + platform 补贴} 当基数。
 * 于是买家用积分抵掉的那部分<b>从商家的货款里出</b>。
 *
 * <p>通道侧本来就有这一步（{@code PayGateway.subsidy} 与两个通道实现都在），
 * <b>零调用方</b> —— 接口在、实现在、钱没打。
 *
 * <p>本类<b>自己构造账单</b>而不是等种子数据凑巧带积分：
 * 靠「有就断言、没有就跳过」的用例会长期显绿而什么都没查。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("积分补差：商家按全额收款，积分的成本由平台出")
class PointsSubsidyFlowTest {

    private static final long SUBSIDY = 2_000L;

    @Autowired
    private SettleService settleService;

    @Autowired
    private BillMapper billMapper;

    @Autowired
    private StubSplitGateway stubGateway;

    @Test
    @DisplayName("★ SettleSource 要把积分抵扣带出来 —— 结算域拿不到它就无从加回")
    void settleSourceCarriesPointsDeduction() {
        var src = new SettleSourcePort.SettleSource(
                "SUB-X", "M0001", "PLATFORM", 8_000L, 1_000L, 0L, null, 1, "ST001", 2_000L, 0L,
                "WECHAT", "MP_WECHAT");

        assertThat(src.payAmount() + src.discountPlatform() + src.pointsDeductMinor())
                .as("结算基数 = 实付 + 平台补贴 + 积分抵扣；缺一项商家就少收一项，"
                        + "而少收不会报错，只会在商家自己对账时表现为「怎么比订单金额少」")
                .isEqualTo(11_000L);
    }

    @Test
    @DisplayName("★★ 有补差额的账单：先补差再分账，两步都留痕")
    void subsidyHappensBeforeSplit() {
        StlBill bill = aBill(SUBSIDY);

        settleService.executeSplit(bill.getSettleNo());

        StlBill after = reload(bill.getSettleNo());
        assertThat(after.getSubsidyAt()).as("补差要真的发生并落时刻").isNotNull();
        assertThat(after.getStatus()).isEqualTo(StlBill.SPLIT);
        assertThat(settleService.splitLogCount(bill.getSettleNo(), StlSplitLog.SUBSIDY))
                .as("补差是一笔真实的通道指令，要有自己的流水")
                .isEqualTo(1L);
        assertThat(settleService.splitLogCount(bill.getSettleNo(), StlSplitLog.SPLIT))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("★★ 补差失败就不分账 —— 只改基数不补钱，比不改更糟")
    void failedSubsidyStopsSplit() {
        StlBill bill = aBill(SUBSIDY);
        stubGateway.failNext(bill.getSubOrderNo());

        settleService.executeSplit(bill.getSettleNo());

        StlBill after = reload(bill.getSettleNo());
        assertThat(after.getStatus())
                .as("补差没成功就把账分了，等于把商家的账做大而钱没打")
                .isEqualTo(StlBill.RETRYING);
        assertThat(after.getSubsidyAt()).isNull();
        assertThat(settleService.splitLogCount(bill.getSettleNo(), StlSplitLog.SPLIT))
                .as("**一条分账指令都不该发出去**")
                .isZero();
    }

    @Test
    @DisplayName("★★ 补差走的是补差额，不是净额 —— 两个数都「看起来合理」，记错了看不出来")
    void subsidyUsesItsOwnAmount() {
        StlBill bill = aBill(SUBSIDY);

        settleService.executeSplit(bill.getSettleNo());

        var logs = billLogs(bill.getSettleNo());
        var subsidyLog = logs.stream()
                .filter(l -> StlSplitLog.SUBSIDY.equals(l.getSplitAction())).findFirst().orElseThrow();
        var splitLog = logs.stream()
                .filter(l -> StlSplitLog.SPLIT.equals(l.getSplitAction())).findFirst().orElseThrow();

        assertThat(subsidyLog.getAmountMinor())
                .as("补差动的是买家用积分抵掉的那部分")
                .isEqualTo(SUBSIDY);
        assertThat(splitLog.getAmountMinor())
                .as("分账动的是平台应收，与补差不是同一个口径")
                .isNotEqualTo(SUBSIDY);
    }

    @Test
    @DisplayName("★ 无补差额的单一条补差指令都不发 —— 零额指令只会变成一条失败日志")
    void zeroSubsidySkipsTheCall() {
        StlBill bill = aBill(0L);

        settleService.executeSplit(bill.getSettleNo());

        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.SPLIT);
        assertThat(settleService.splitLogCount(bill.getSettleNo(), StlSplitLog.SUBSIDY)).isZero();
        assertThat(reload(bill.getSettleNo()).getSubsidyAt()).isNull();
    }

    @Test
    @DisplayName("★ 补差幂等：重复执行不会重复补钱")
    void subsidyIsIdempotent() {
        StlBill bill = aBill(SUBSIDY);

        settleService.executeSplit(bill.getSettleNo());
        settleService.executeSplit(bill.getSettleNo());

        assertThat(settleService.splitLogCount(bill.getSettleNo(), StlSplitLog.SUBSIDY))
                .as("重复补差是凭空送钱，比重复分账更严重")
                .isEqualTo(1L);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("★★ 通道不支持补差 → 转人工，而不是发起一次注定失败的补差")
    void unsupportedChannelGoesManual() {
        StlBill bill = aBill(300L);
        bill.setPayChannel("CASH_ON_DELIVERY");   // 不在 sys_pay_channel 里
        billMapper.updateById(bill);

        settleService.executeSplit(bill.getSettleNo());

        StlBill after = reload(bill.getSettleNo());
        // supports_subsidy 这一列建出来就是为了拦这里，而此前**零读取** ——
        // 不具备补差能力的通道照样走到分账，然后失败、转重试，
        // 而根因（通道压根没这个能力）要翻网关日志才看得出来
        assertThat(after.getStatus()).isEqualTo(StlBill.MANUAL);
        assertThat(after.getLastError()).contains("不支持积分补差");
    }

    // ---------------------------------------------------------------- helpers

    private StlBill aBill(long subsidyMinor) {
        StlBill b = new StlBill();
        b.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
        b.setSubOrderNo("SUBSD" + System.nanoTime() % 100_000_000);
        b.setOrderNo("ORDSD" + System.nanoTime() % 100_000_000);
        b.setEntityNo("M0001");
        b.setStoreNo("ST001");
        b.setPayMerchantNo("PM-TEST");
        b.setGrossMinor(11_000L);
        b.setCommissionMinor(550L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(10_450L);
        b.setCommissionRate(500);
        // 补差只在**直连**路径上存在：钱在商家二级户，积分抵扣让他少收，平台要补进去。
        // 归集路径下钱本就在平台手里，应付已按全额算过 —— 再补一次就是重复付款，
        // 所以 executeSplit 会在执行点断言。造带补差的单必须显式声明这条路径。
        b.setFundsMode(ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_DIRECT);
        // 通道必须真的支持补差 —— sys_pay_channel.supports_subsidy 现在会在
        // executeSplit 里被读到。不设的话（null 查不到 → 判 false）整单转 MANUAL
        b.setPayChannel("WECHAT");
        b.setSubsidyMinor(subsidyMinor);
        b.setStatus(StlBill.PENDING);
        b.setRetryCount(0);
        billMapper.insert(b);
        return b;
    }

    private StlBill reload(String settleNo) {
        return billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                .eq(StlBill::getSettleNo, settleNo).last("LIMIT 1"));
    }

    @Autowired
    private ai.neargo.shop.settle.mapper.SettleMappers.SplitLogMapper splitLogMapper;

    private java.util.List<StlSplitLog> billLogs(String settleNo) {
        return splitLogMapper.selectList(Wrappers.<StlSplitLog>lambdaQuery()
                .eq(StlSplitLog::getSettleNo, settleNo));
    }
}
