package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.StubSplitGateway;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSplitLog;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Current funding guard: a bill alone never authorizes a points subsidy.
 * Funded payout, external failure, reply loss and idempotency are exercised with real
 * ledger positions and durable tickets in PerOrderSettlementTest / PointsPayoutPreparationTest.
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
                "WECHAT", "MP_WECHAT", 0L, 0L, null);

        assertThat(src.payAmount() + src.discountPlatform() + src.pointsDeductMinor())
                .as("结算基数 = 实付 + 平台补贴 + 积分抵扣；缺一项商家就少收一项，"
                        + "而少收不会报错，只会在商家自己对账时表现为「怎么比订单金额少」")
                .isEqualTo(11_000L);
    }

    @Test
    @DisplayName("缺少抵扣持仓的账单不能补差或分账")
    void subsidyHappensBeforeSplit() {
        StlBill bill=aBill(SUBSIDY);
        assertThatThrownBy(() -> settleService.executeSplit(bill.getSettleNo()))
                .hasMessage("POINTS_REDEMPTION_NOT_UNIQUE");
        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.PENDING);
        assertThat(reload(bill.getSettleNo()).getSubsidyAt()).isNull();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SPLIT)).isZero();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SUBSIDY)).isZero();
    }

    @Test
    @DisplayName("无持仓时不得触发补差通道，连失败指令也不能发送")
    void failedSubsidyStopsSplit() {
        StlBill bill=aBill(SUBSIDY);
        stubGateway.failNext(bill.getSubOrderNo());
        assertThatThrownBy(() -> settleService.executeSplit(bill.getSettleNo()))
                .hasMessage("POINTS_REDEMPTION_NOT_UNIQUE");
        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.PENDING);
        assertThat(reload(bill.getSettleNo()).getSubsidyAt()).isNull();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SPLIT)).isZero();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SUBSIDY)).isZero();
    }

    @Test
    @DisplayName("账单中的补差金额不能取代真实抵扣凭据")
    void subsidyUsesItsOwnAmount() {
        StlBill bill=aBill(SUBSIDY);
        assertThatThrownBy(() -> settleService.executeSplit(bill.getSettleNo()))
                .hasMessage("POINTS_REDEMPTION_NOT_UNIQUE");
        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.PENDING);
        assertThat(reload(bill.getSettleNo()).getSubsidyAt()).isNull();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SPLIT)).isZero();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SUBSIDY)).isZero();
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
    @DisplayName("反复执行无凭据账单不能生成任何资金指令")
    void subsidyIsIdempotent() {
        StlBill bill=aBill(SUBSIDY);
        assertThatThrownBy(() -> settleService.executeSplit(bill.getSettleNo()))
                .hasMessage("POINTS_REDEMPTION_NOT_UNIQUE");
        assertThatThrownBy(() -> settleService.executeSplit(bill.getSettleNo()))
                .hasMessage("POINTS_REDEMPTION_NOT_UNIQUE");
        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.PENDING);
        assertThat(reload(bill.getSettleNo()).getSubsidyAt()).isNull();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SPLIT)).isZero();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SUBSIDY)).isZero();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("无凭据且通道不支持补差时同样阻断资金指令")
    void unsupportedChannelGoesManual() {
        StlBill bill=aBill(300L);
        bill.setPayChannel("CASH_ON_DELIVERY");
        billMapper.updateById(bill);
        assertThatThrownBy(() -> settleService.executeSplit(bill.getSettleNo()))
                .hasMessage("POINTS_REDEMPTION_NOT_UNIQUE");
        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.PENDING);
        assertThat(reload(bill.getSettleNo()).getSubsidyAt()).isNull();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SPLIT)).isZero();
        assertThat(settleService.splitLogCount(bill.getSettleNo(),StlSplitLog.SUBSIDY)).isZero();
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
        b.setCurrency("CNY");
        b.setSplitAmountMinor(550L);
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
    private ai.neargo.shop.pay.mapper.SettleMappers.SplitLogMapper splitLogMapper;

    private java.util.List<StlSplitLog> billLogs(String settleNo) {
        return splitLogMapper.selectList(Wrappers.<StlSplitLog>lambdaQuery()
                .eq(StlSplitLog::getSettleNo, settleNo));
    }
}
