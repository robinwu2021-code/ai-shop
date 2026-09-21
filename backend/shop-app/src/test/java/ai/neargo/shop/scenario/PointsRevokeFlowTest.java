package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.PointsConfig;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPointsPool;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsLedgerMapper;
import ai.neargo.shop.pay.setting.PaySettingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整单退款收回已发放的购物积分（待办设计 P2c）。
 *
 * <p>口径（用户 2026-09-21 拍板）：还在待生效的从 pending 扣回；已经转正而且花掉了的，
 * <b>余额可以扣成负数</b>，下次获得时先抵（{@code points.config.allowNegativeBalance}，缺省允许）。
 *
 * <p>本类的账户与流水一律落在 market = {@value #MKT}：资金池恒等式按 market 分开算，
 * 不这样隔开的话，这里造的余额会让 CN 市场的恒等式巡检用例莫名失衡。
 */
@SpringBootTest
@ActiveProfiles("test")
class PointsRevokeFlowTest {

    private static final String MKT = "RVK";
    private static int seq = 0;

    @Autowired
    private PointsService points;
    @Autowired
    private PointsAccountMapper accountMapper;
    @Autowired
    private PointsLedgerMapper ledgerMapper;
    @Autowired
    private BillMapper billMapper;
    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.PointsPoolMapper poolMapper;
    @Autowired
    private PaySettingService paySettings;

    @Test
    @DisplayName("★★★ 待生效的分：从 pending 扣回，且转正任务不再把它转正")
    void pendingRevoked() {
        String user = user(0, 50);
        String sub = earn(user, 50, false);

        assertThat(points.revokeEarned(sub, "整单退款")).isEqualTo(50);
        assertThat(account(user).getPendingBalance()).isZero();

        points.activateDuePoints();
        assertThat(account(user).getBalance()).as("收回了还被转正 = 分凭空又回来了").isZero();
    }

    @Test
    @DisplayName("★★★ 已转正且花掉了：余额扣成负数（缺省允许）")
    void activeSpentGoesNegative() {
        String user = user(10, 0);
        String sub = earn(user, 50, true);

        assertThat(points.revokeEarned(sub, "整单退款")).isEqualTo(50);
        assertThat(account(user).getBalance()).isEqualTo(-40L);
    }

    @Test
    @DisplayName("★★ 不许为负时只扣到 0，差额写进流水备注供对账（关着的那一半）")
    void floorAtZeroWhenDisallowed() {
        String before = paySettings.get(PointsConfig.KEY, PointsConfig.DEFAULT_JSON);
        paySettings.put(PointsConfig.KEY, """
                {"perMinor":1,"maxDeductRatio":0.3,"earnPerMinor":0.01,"inactiveDays":365,\
                "pendingDays":7,"allowNegativeBalance":false}""", "TEST");
        try {
            String user = user(10, 0);
            String sub = earn(user, 50, true);

            assertThat(points.revokeEarned(sub, "整单退款")).isEqualTo(10);
            assertThat(account(user).getBalance()).isZero();
            assertThat(revokeRow(sub).getRemark()).contains("未收回 40");
        } finally {
            paySettings.put(PointsConfig.KEY, before, "TEST");
        }
    }

    @Test
    @DisplayName("★★ 重复收回只收一次 —— 退款链路会对同一单调多次")
    void idempotent() {
        String user = user(100, 0);
        String sub = earn(user, 30, true);

        points.revokeEarned(sub, "整单退款");
        assertThat(points.revokeEarned(sub, "整单退款")).isZero();
        assertThat(account(user).getBalance()).isEqualTo(70L);
    }

    @Test
    @DisplayName("★★ 已结算收过发分费的单：按收回的分记一笔 RECOVERY 出池；没结算的不出池")
    void poolRecoveryOnlyWhenFeeCollected() {
        String user = user(100, 0);
        String settled = earn(user, 30, true);
        bill(settled, 30L);
        String unsettled = earn(user, 20, true);

        points.revokeEarned(settled, "整单退款");
        points.revokeEarned(unsettled, "整单退款");

        assertThat(recoveryOf(settled)).as("池里那笔钱不再欠任何人，不出池恒等式永远多一截").isEqualTo(30L);
        assertThat(recoveryOf(unsettled)).as("池子从没收过这笔钱，出了就扣成负的").isZero();
    }

    // ------------------------------------------------------------------ P2b 分账后退积分

    @Test
    @DisplayName("★★★ 分账后整单退款：分退回，补差收回了就对冲入池 —— 本市场的池子回到原位")
    void confirmedRefundWithSubsidyBack() {
        String user = user(0, 0);
        String sub = confirmedUse(user, 300);
        long poolBefore = poolOf();
        points.recordPoolFlow(StlPointsPool.MERCHANT_PAY, 300, "M0001", sub, null, MKT); // 分账时那笔出池
        reversedBill(sub, 300L, true);

        assertThat(points.refundConfirmed(sub, "整单退款")).isEqualTo(300);
        assertThat(account(user).getBalance()).isEqualTo(300L);
        assertThat(poolOf()).as("补差回来了却不入池，恒等式永远差这一截").isEqualTo(poolBefore);
        assertThat(points.refundConfirmed(sub, "整单退款")).as("重复退 = 凭空印分").isZero();
    }

    @Test
    @DisplayName("★★ 补差没收回：分照退，池子不入账 —— 不能用一笔假入账盖住待追回的钱")
    void confirmedRefundSubsidyStuck() {
        String user = user(0, 0);
        String sub = confirmedUse(user, 200);
        points.recordPoolFlow(StlPointsPool.MERCHANT_PAY, 200, "M0001", sub, null, MKT);
        long poolAfterPay = poolOf();
        reversedBill(sub, 200L, false);

        assertThat(points.refundConfirmed(sub, "整单退款")).isEqualTo(200);
        assertThat(account(user).getBalance()).as("平台追款失败不能让买家吃亏").isEqualTo(200L);
        assertThat(poolOf()).isEqualTo(poolAfterPay);
    }

    private String confirmedUse(String userNo, long pts) {
        String sub = "SUB-RVK-" + System.nanoTime() % 1_000_000_000L + "-" + (++seq);
        PtsUserLedger l = new PtsUserLedger();
        l.setLedgerNo(BizKey.next(BizKey.POINTS_LEDGER));
        l.setUserNo(userNo);
        l.setBizType(PtsUserLedger.USE);
        l.setStatus(PtsUserLedger.CONFIRMED);
        l.setPoints(-pts);
        l.setAmountMinor(pts);
        l.setBalanceAfter(0L);
        l.setSubOrderNo(sub);
        l.setMarket(MKT);
        l.setAcceptorMerchantNo("M0001");
        ledgerMapper.insert(l);
        return sub;
    }

    /** 已回退的结算单；subsidyBack = 补差回退成功（subsidy_at 已清） */
    private void reversedBill(String subOrderNo, long subsidy, boolean subsidyBack) {
        StlBill b = new StlBill();
        b.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
        b.setSubOrderNo(subOrderNo);
        b.setOrderNo("ORD-" + subOrderNo);
        b.setEntityNo("M0001");
        b.setGrossMinor(1000L);
        b.setCommissionMinor(0L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(1000L);
        b.setCommissionRate(0);
        b.setPayChannel("WECHAT");
        b.setSubsidyMinor(subsidy);
        b.setSubsidyAt(subsidyBack ? null : System.currentTimeMillis());
        b.setStatus(StlBill.REVERSED);
        b.setRetryCount(0);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
    }

    private long poolOf() {
        return DataScopeContext.executeWithoutScope(() -> poolMapper.selectList(
                        Wrappers.<StlPointsPool>lambdaQuery().eq(StlPointsPool::getMarket, MKT)))
                .stream().mapToLong(f -> StlPointsPool.IN.equals(f.getDirection())
                        ? f.getAmountMinor() : -f.getAmountMinor()).sum();
    }

    // ------------------------------------------------------------------ helpers

    private String user(long balance, long pending) {
        String userNo = "U-RVK-" + System.nanoTime() % 1_000_000_000L + "-" + (++seq);
        PtsUserAccount a = new PtsUserAccount();
        a.setUserNo(userNo);
        a.setBalance(balance);
        a.setPendingBalance(pending);
        a.setTotalEarn(balance + pending);
        a.setTotalUse(0L);
        a.setMarket(MKT);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(a);
        return userNo;
    }

    /** 造一条发放流水；activated = 已转正（余额里已经有这笔，由调用方在 user() 里给好） */
    private String earn(String userNo, long pts, boolean activated) {
        String sub = "SUB-RVK-" + System.nanoTime() % 1_000_000_000L + "-" + (++seq);
        PtsUserLedger l = new PtsUserLedger();
        l.setLedgerNo(BizKey.next(BizKey.POINTS_LEDGER));
        l.setUserNo(userNo);
        l.setBizType(PtsUserLedger.EARN);
        l.setPoints(pts);
        l.setBalanceAfter(0L);
        l.setSubOrderNo(sub);
        l.setMarket(MKT);
        l.setIssuerMerchantNo("M0001");
        long now = System.currentTimeMillis();
        l.setAvailableAt(now - 1000);
        if (activated) {
            l.setActivatedAt(now - 500);
        }
        ledgerMapper.insert(l);
        return sub;
    }

    private void bill(String subOrderNo, long pointsFee) {
        StlBill b = new StlBill();
        b.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
        b.setSubOrderNo(subOrderNo);
        b.setOrderNo("ORD-" + subOrderNo);
        b.setEntityNo("M0001");
        b.setGrossMinor(1000L);
        b.setCommissionMinor(0L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(1000L - pointsFee);
        b.setCommissionRate(0);
        b.setPayChannel("WECHAT");
        b.setPointsFeeMinor(pointsFee);
        b.setStatus(StlBill.PENDING);
        b.setRetryCount(0);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
        // 结算时那笔发分费真的进过池子 —— 与 SettleServiceImpl 同一个动作，保持本市场的账平
        points.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, pointsFee, "M0001", b.getSettleNo(), null, MKT);
    }

    private PtsUserAccount account(String userNo) {
        return accountMapper.selectOne(Wrappers.<PtsUserAccount>lambdaQuery()
                .eq(PtsUserAccount::getUserNo, userNo).last("LIMIT 1"));
    }

    private PtsUserLedger revokeRow(String sub) {
        return ledgerMapper.selectOne(Wrappers.<PtsUserLedger>lambdaQuery()
                .eq(PtsUserLedger::getSubOrderNo, sub)
                .eq(PtsUserLedger::getBizType, PtsUserLedger.REVOKE).last("LIMIT 1"));
    }

    private long recoveryOf(String sub) {
        return DataScopeContext.executeWithoutScope(() -> poolMapper.selectList(
                        Wrappers.<StlPointsPool>lambdaQuery()
                                .eq(StlPointsPool::getPoolType, StlPointsPool.RECOVERY)
                                .eq(StlPointsPool::getRefNo, sub)))
                .stream().mapToLong(StlPointsPool::getAmountMinor).sum();
    }
}
