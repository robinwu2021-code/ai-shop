package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlSplitLog;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.settle.StubSplitGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台端结算查询（落地清单 P2-9）。
 *
 * <p>补的是一个完整的盲区：结算单与分账指令一直只存在于库里，
 * <b>运营端没有任何入口</b>。出问题时唯一的办法是让人去查库。
 *
 * <p>两条最要紧的断言：<b>两条轨道都要看得到</b>（分开查会让一家同时有自营店和
 * 第三方店的商家需要在两个页面之间对照），以及<b>失败的指令也要给</b>
 * （出问题时要看的恰恰是它们）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("平台端结算查询：钱到哪一步了，以及为什么没到")
class OpsSettleQueryFlowTest {

    @Autowired
    private SettleService settleService;

    @Autowired
    private BillMapper billMapper;

    @Autowired
    private StubSplitGateway stubGateway;

    @Test
    @DisplayName("★★ 自营与第三方都在同一个列表里 —— 不该按经营模式分成两个入口")
    void bothTracksInOneList() {
        StlBill selfOp = aBill(MerchantQueryPort.MODE_SELF_OPERATED, StlBill.PENDING_RECON);
        StlBill thirdParty = aBill(MerchantQueryPort.MODE_THIRD_PARTY, StlBill.PENDING);

        var all = settleService.opsBills(null, "M0001", null);

        assertThat(all).extracting(v -> v.settleNo())
                .as("运营要回答的是「这家店的钱到哪一步了」，"
                        + "分开查等于让人在两个页面之间对照才拼得出全貌")
                .contains(selfOp.getSettleNo(), thirdParty.getSettleNo());
    }

    @Test
    @DisplayName("★ 按经营模式筛得动 —— 全都给和只给一种是两种需求")
    void filterByBusinessMode() {
        StlBill selfOp = aBill(MerchantQueryPort.MODE_SELF_OPERATED, StlBill.PENDING_RECON);
        StlBill thirdParty = aBill(MerchantQueryPort.MODE_THIRD_PARTY, StlBill.PENDING);

        var onlySelf = settleService.opsBills(null, "M0001", MerchantQueryPort.MODE_SELF_OPERATED);

        assertThat(onlySelf).extracting(v -> v.settleNo())
                .contains(selfOp.getSettleNo())
                .doesNotContain(thirdParty.getSettleNo());
    }

    @Test
    @DisplayName("★★ 失败的分账指令也要查得到 —— 只给成功的等于把答案藏起来")
    void failedInstructionsAreVisible() {
        StlBill bill = aBill(MerchantQueryPort.MODE_THIRD_PARTY, StlBill.PENDING);
        stubGateway.failNext(bill.getSubOrderNo());

        settleService.executeSplit(bill.getSettleNo());

        var logs = settleService.opsSplitLogs(bill.getSettleNo(), null);
        assertThat(logs).as("「为什么这单没分成」的答案就在这条失败记录里").isNotEmpty();
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.splitAction()).isEqualTo(StlSplitLog.SPLIT);
            assertThat(l.result()).isNotEqualTo("SUCCESS");
        });
    }

    @Test
    @DisplayName("★ 按动作筛：补差与分账是两种指令，混在一起看不出顺序对不对")
    void filterByAction() {
        StlBill bill = aBill(MerchantQueryPort.MODE_THIRD_PARTY, StlBill.PENDING);
        bill.setSubsidyMinor(1_000L);
        billMapper.updateById(bill);

        settleService.executeSplit(bill.getSettleNo());

        assertThat(settleService.opsSplitLogs(bill.getSettleNo(), StlSplitLog.SUBSIDY))
                .hasSize(1);
        assertThat(settleService.opsSplitLogs(bill.getSettleNo(), StlSplitLog.SPLIT))
                .hasSize(1);
    }

    @Test
    @DisplayName("★ 按状态筛得动 —— 运营台默认要看的是「卡住的那些」")
    void filterByStatus() {
        StlBill pending = aBill(MerchantQueryPort.MODE_THIRD_PARTY, StlBill.PENDING);

        var onlyPending = settleService.opsBills(StlBill.PENDING, "M0001", null);

        assertThat(onlyPending).extracting(v -> v.settleNo()).contains(pending.getSettleNo());
        assertThat(onlyPending).allSatisfy(v -> assertThat(v.status()).isEqualTo(StlBill.PENDING));
    }

    private StlBill aBill(String businessMode, String status) {
        StlBill b = new StlBill();
        b.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
        b.setSubOrderNo("OSQ" + System.nanoTime() % 100_000_000);
        b.setOrderNo("OSQO" + System.nanoTime() % 100_000_000);
        b.setEntityNo("M0001");
        b.setStoreNo("ST001");
        b.setPayMerchantNo("PM-TEST");
        b.setGrossMinor(10_000L);
        b.setCommissionMinor(500L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(9_500L);
        b.setCommissionRate(500);
        // 补差只在**直连**路径上存在：钱在商家二级户，积分抵扣让他少收，平台要补进去。
        // 归集路径下钱本就在平台手里，应付已按全额算过 —— 再补一次就是重复付款，
        // 所以 executeSplit 会在执行点断言。造带补差的单必须显式声明这条路径。
        b.setFundsMode(ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_DIRECT);
        b.setSubsidyMinor(0L);
        b.setBusinessMode(businessMode);
        b.setStatus(status);
        b.setRetryCount(0);
        billMapper.insert(b);
        return b;
    }
}
