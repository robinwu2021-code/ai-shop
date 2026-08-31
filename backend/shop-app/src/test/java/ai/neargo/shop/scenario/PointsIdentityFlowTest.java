package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.entity.StlPointsPool;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsLedgerMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 恒等式自检：<b>池子里的钱 == 还欠着用户的钱</b>。
 *
 * <p>积分域-完整方案称它「是这套设计<b>唯一的自检手段</b>，违反即告警」，
 * 而这个校验此前<b>不存在</b> —— 两边的数只在 ops 看板上并排显示，
 * 没有任何人比较它们。
 *
 * <p><b>本测试用一个独立市场</b>（不是 CN）：共享库里已有别的会话与既往用例
 * 留下的账户和池子流水，在 CN 上断言「差额为零」必然红，而红的原因与本次改动无关。
 * 换个市场隔离，等式本身的逻辑照样验得到。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("恒等式自检：池子里的钱要等于还欠着的钱")
class PointsIdentityFlowTest {

    /** 独立市场：CN 上有存量数据，断言不了绝对值 */
    private static final String MARKET = "ID_TEST";

    @Autowired
    private PointsService pointsService;

    @Autowired
    private PointsAccountMapper accountMapper;

    @Autowired
    private PointsLedgerMapper ledgerMapper;

    @Test
    @DisplayName("★★★ 发分收费 → 平；只发分不收费 → 失衡")
    void grantWithoutFeeBreaksIdentity() {
        long before = pointsService.checkIdentity(MARKET).diffMinor();

        // 发 500 分给用户，但**不收费用金** —— 正是本轮之前一直在发生的事
        anAccount(500L, 0L);

        assertThat(pointsService.checkIdentity(MARKET).diffMinor())
                .as("平台欠了用户 500 分的钱，而池子里一分没多 —— 这就是失衡")
                .isEqualTo(before - 500L);

        // 补上入账，等式恢复
        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, 500L,
                "M0001", "IDT" + System.nanoTime() % 100_000_000L, "WECHAT", MARKET);

        assertThat(pointsService.checkIdentity(MARKET).diffMinor()).isEqualTo(before);
    }

    @Test
    @DisplayName("★★★ PENDING 的抵扣要算进「还欠着的钱」—— 漏了它每来一单就误报一次")
    void pendingDeductionCountsAsOwed() {
        long before = pointsService.checkIdentity(MARKET).diffMinor();

        /*
         * 模拟一次**完整的**下单抵扣，三步缺一不可 —— 第一版漏了第一步，
         * 测试当场红了，而它红得对：**没发过分就抵扣**这个状态在真实链路里不存在。
         *
         *   ① 发分时商家付过费用金 → 池子里有这 300 分对应的钱
         *   ② 用户花掉 → 余额扣光（这里直接建成 0/0）
         *   ③ 兑付还没成立 → 一条 PENDING 的 USE
         *
         * 此刻这笔钱**已经不在用户账上、也还没付给收单方** —— 它正躺在池子里等着。
         * 不把它算进「还欠着的钱」，等式会在每个未结算订单上都差一截，
         * 而告警一旦天天响就等于没有告警。
         */
        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, 300L,
                "M0001", "IDT" + System.nanoTime() % 100_000_000L, "WECHAT", MARKET);
        String user = anAccount(0L, 0L);
        aPendingUse(user, 300L, 300L);

        assertThat(pointsService.checkIdentity(MARKET).diffMinor())
                .as("PENDING 的抵扣被算进去了，等式不该因为一次正常下单而失衡")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("★★ 未兑付金额要能单独读出来 —— 排查第一步是「差在哪一侧」")
    void pendingUseIsReported() {
        String user = anAccount(0L, 0L);
        long before = pointsService.checkIdentity(MARKET).pendingUseMinor();

        aPendingUse(user, 200L, 200L);

        assertThat(pointsService.checkIdentity(MARKET).pendingUseMinor())
                .isEqualTo(before + 200L);
    }

    @Test
    @DisplayName("★★ 待生效的分同样是欠款 —— 它只是还不能花，不是不存在")
    void pendingBalanceIsAlsoOwed() {
        long before = pointsService.checkIdentity(MARKET).diffMinor();

        // 全部记在 pending_balance 上（还没过售后期）
        anAccount(0L, 400L);

        assertThat(pointsService.checkIdentity(MARKET).diffMinor())
                .as("只算可用余额的话，售后期内发出去的分在账上凭空消失")
                .isEqualTo(before - 400L);
    }

    @Test
    @DisplayName("★ balanced() 与 diffMinor() 说的是同一件事")
    void balancedMatchesDiff() {
        var c = pointsService.checkIdentity(MARKET);
        assertThat(c.balanced()).isEqualTo(c.diffMinor() == 0);
    }

    // ---------------------------------------------------------------- fixtures

    private String anAccount(long balance, long pending) {
        String no = "ID" + System.nanoTime() % 100_000_000L;
        PtsUserAccount a = new PtsUserAccount();
        a.setUserNo(no);
        a.setMarket(MARKET);
        a.setBalance(balance);
        a.setPendingBalance(pending);
        a.setTotalEarn(balance + pending);
        a.setLastActiveAt(System.currentTimeMillis());
        a.setExpireAt(System.currentTimeMillis() + 86_400_000L);
        accountMapper.insert(a);
        return no;
    }

    private void aPendingUse(String userNo, long points, long amountMinor) {
        PtsUserLedger use = new PtsUserLedger();
        use.setLedgerNo("PLID" + System.nanoTime() % 100_000_000L);
        use.setUserNo(userNo);
        use.setBizType("USE");
        use.setPoints(-points);
        use.setBalanceAfter(0L);
        use.setAmountMinor(amountMinor);
        use.setAcceptorMerchantNo("M0001");
        use.setSubOrderNo("SUBID" + System.nanoTime() % 100_000_000L);
        use.setStatus("PENDING");
        use.setMarket(MARKET);
        ledgerMapper.insert(use);
    }
}
