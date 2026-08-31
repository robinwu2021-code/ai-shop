package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.entity.StlPointsPool;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsLedgerMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsPoolMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抵扣兑付成立：{@code USE} 从 {@code PENDING} 走到 {@code CONFIRMED} 并出池。
 *
 * <p><b>此前这一步整个不存在</b>，而它是恒等式的另一半：
 * 抵扣时只记预占（订单还可能取消或退款），池子一分没动；
 * 兑付时才真的把钱付给收单商家。<b>缺了兑付，池子只进不出</b> ——
 * 与 {@code EXPIRE_INCOME} 缺失时是同一个病，失衡量随成交量单调增长。
 *
 * <p>症状同样是「看着还挺平」：B 端「本期积分支出」永远是 0，
 * 而 PENDING 那一堆挂在流水里，没有任何页面会显示它们。
 *
 * <p><b>时点的选择</b>：跟着 {@code stl_bill} 的时钟走，不另立一套「积分售后期」。
 * 账单里已经有这套语义 —— {@code accruedAt}（计提）与 {@code splitAt}（钱真的动）。
 * 两条路径各自的落点见 {@code SettleServiceImpl}：
 * 直连在 {@code executeSplit} 成功后，归集在 {@code markPaid}。
 * <b>自营单根本不走分账</b>，只挂在分账上的话归集路径永远确认不了。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("抵扣兑付：池子要真的出账，且只出一次")
class PointsConfirmFlowTest {

    @Autowired
    private PointsService pointsService;

    @Autowired
    private PointsAccountMapper accountMapper;

    @Autowired
    private PointsLedgerMapper ledgerMapper;

    @Autowired
    private PointsPoolMapper poolMapper;

    @Test
    @DisplayName("★★★ 确认后 USE 置 CONFIRMED，并记一笔 MERCHANT_PAY 出池")
    void confirmMovesMoneyOutOfPool() {
        String sub = aPendingUse(300, 300L, "MC1");

        int n = pointsService.confirmDeduction(sub);

        assertThat(n).isEqualTo(1);
        assertThat(useOf(sub).getStatus()).isEqualTo("CONFIRMED");

        List<StlPointsPool> flows = poolOf(sub);
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).getPoolType()).isEqualTo(StlPointsPool.MERCHANT_PAY);
        // 出池 —— 平台把钱付出去了。记成 IN 的话恒等式会朝反方向失衡
        assertThat(flows.get(0).getDirection()).isEqualTo(StlPointsPool.OUT);
        assertThat(flows.get(0).getAmountMinor()).isEqualTo(300L);
        // 收单方要落到流水上：不记的话「这笔钱付给了谁」查不出来
        assertThat(flows.get(0).getEntityNo()).isEqualTo("MC1");
    }

    @Test
    @DisplayName("★★★ 重复确认不重复出池 —— 结算链路会对同一单调多次")
    void confirmIsIdempotent() {
        String sub = aPendingUse(300, 300L, "MC1");

        pointsService.confirmDeduction(sub);
        int again = pointsService.confirmDeduction(sub);

        // 分账重试、账单重推都会再调一次。第二次要静默返回 0 而不是报错 ——
        // 报错会让整条结算失败，而失败的原因跟积分毫无关系
        assertThat(again).isZero();
        assertThat(poolOf(sub)).hasSize(1);
    }

    @Test
    @DisplayName("★★★ 已退回的不会被重新确认 —— 否则退过的单又被付一遍钱")
    void reversedIsNeverConfirmed() {
        String sub = aPendingUse(300, 300L, "MC1");
        pointsService.reverse(sub, "用户取消");

        int n = pointsService.confirmDeduction(sub);

        // 这条是按状态过滤而不是按子单号覆写的理由：
        // 覆写的写法会把退过的单重新置成 CONFIRMED 并出池，而账面看不出异常
        assertThat(n).isZero();
        assertThat(useOf(sub).getStatus()).isEqualTo("REVERSED");
        assertThat(poolOf(sub)).isEmpty();
    }

    @Test
    @DisplayName("★ 没用积分的单：静默返回 0，不报错")
    void noDeductionIsSilent() {
        assertThat(pointsService.confirmDeduction("SUB-NOT-EXIST")).isZero();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * 直接造一条 PENDING 的 USE 流水，而不是走 {@code deductOnPlace}。
     *
     * <p>走全链路要先备账户余额、商家开关、门店、订单 —— 那些是
     * {@code PointsDeductFlowTest} 在测的东西。这里测的是**兑付这一步本身**，
     * 前置越多，红了越难判断是哪一环。
     */
    private String aPendingUse(long points, long amountMinor, String acceptor) {
        String user = "PC" + System.nanoTime() % 100_000_000L;
        PtsUserAccount a = new PtsUserAccount();
        a.setUserNo(user);
        a.setMarket("CN");
        a.setBalance(0L);
        a.setPendingBalance(0L);
        a.setTotalEarn(0L);
        a.setLastActiveAt(System.currentTimeMillis());
        a.setExpireAt(System.currentTimeMillis() + 86_400_000L);
        accountMapper.insert(a);

        String sub = "SUBPC" + System.nanoTime() % 100_000_000L;
        PtsUserLedger use = new PtsUserLedger();
        use.setLedgerNo("PLPC" + System.nanoTime() % 100_000_000L);
        use.setUserNo(user);
        use.setBizType("USE");
        use.setPoints(-points);
        use.setBalanceAfter(0L);
        use.setAmountMinor(amountMinor);
        use.setAcceptorMerchantNo(acceptor);
        use.setSubOrderNo(sub);
        use.setStatus("PENDING");
        use.setMarket("CN");
        ledgerMapper.insert(use);
        return sub;
    }

    private PtsUserLedger useOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectOne(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getSubOrderNo, subOrderNo)
                        .eq(PtsUserLedger::getBizType, "USE")
                        .last("LIMIT 1")));
    }

    private List<StlPointsPool> poolOf(String refNo) {
        return DataScopeContext.executeWithoutScope(() ->
                poolMapper.selectList(Wrappers.<StlPointsPool>lambdaQuery()
                        .eq(StlPointsPool::getRefNo, refNo)));
    }
}
