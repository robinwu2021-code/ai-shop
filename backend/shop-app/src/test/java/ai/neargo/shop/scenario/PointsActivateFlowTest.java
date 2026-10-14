package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsLedgerMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待生效积分转正 —— 补上让整条积分链跑起来的那一环。
 *
 * <p><b>此前这一环整个不存在</b>：发放时不写 {@code available_at}（全库零写入点），
 * 也没有任何任务在转正。后果链全程不报错：
 * <pre>
 *   available_at 恒 NULL  →  没人扫得到  →  balance 恒 0
 *   →  maxUsablePoints 算出 0  →  抵扣永远抵不了
 * </pre>
 * 用户看得见分在涨（pending_balance），却一分也花不出去。
 *
 * <p>所以这里断言的不是「方法能不能调通」，而是<b>钱真的从 pending 挪进了 balance</b>，
 * 以及<b>重复执行不会重复加</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("积分转正：待生效的分要真的变成能花的")
class PointsActivateFlowTest {

    @Autowired
    private PointsService pointsService;

    @Autowired
    private PointsAccountMapper accountMapper;

    @Autowired
    private PointsLedgerMapper ledgerMapper;

    @Test
    @DisplayName("★★ 到点的分从 pending 挪进 balance —— 这一步此前完全不存在")
    void duePointsMoveToBalance() {
        String user = anUser(0, 100);
        aLedger(user, 100, System.currentTimeMillis() - 1000);

        int n = pointsService.activateDuePoints();

        assertThat(n).isGreaterThanOrEqualTo(1);
        PtsUserAccount a = account(user);
        assertThat(a.getBalance()).isEqualTo(100L);
        assertThat(a.getPendingBalance()).isZero();
    }

    @Test
    @DisplayName("★★ 没到点的不动 —— 售后期内退款要连分一起收回，已花掉的收不回来")
    void notDueStaysPending() {
        String user = anUser(0, 50);
        aLedger(user, 50, System.currentTimeMillis() + 86_400_000L);

        pointsService.activateDuePoints();

        PtsUserAccount a = account(user);
        assertThat(a.getBalance()).isZero();
        assertThat(a.getPendingBalance()).isEqualTo(50L);
    }

    @Test
    @DisplayName("★★ 跑两次不会加两次 —— 幂等靠 activated_at，不靠余额守卫兜底")
    void idempotentAcrossRuns() {
        String user = anUser(0, 80);
        aLedger(user, 80, System.currentTimeMillis() - 1000);

        pointsService.activateDuePoints();
        pointsService.activateDuePoints();

        PtsUserAccount a = account(user);
        assertThat(a.getBalance()).isEqualTo(80L);
        // 只按时间判的话第二次会再扫到同一行；标记写死之后它不再进候选集
        assertThat(a.getPendingBalance()).isZero();
    }

    @Test
    @DisplayName("★ 转正后流水打上时间戳 —— 「什么时候能花的」要查得到")
    void stampsActivatedAt() {
        String user = anUser(0, 30);
        String no = aLedger(user, 30, System.currentTimeMillis() - 1000);

        pointsService.activateDuePoints();

        PtsUserLedger row = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectOne(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getLedgerNo, no).last("LIMIT 1")));
        assertThat(row.getActivatedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- fixtures

    private PtsUserAccount account(String userNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                accountMapper.selectOne(Wrappers.<PtsUserAccount>lambdaQuery()
                        .eq(PtsUserAccount::getUserNo, userNo).last("LIMIT 1")));
    }

    private String anUser(long balance, long pending) {
        String no = "PA" + System.nanoTime() % 100_000_000L;
        PtsUserAccount a = new PtsUserAccount();
        a.setUserNo(no);
        a.setMarket("CN");
        a.setBalance(balance);
        a.setPendingBalance(pending);
        a.setTotalEarn(pending);
        a.setLastActiveAt(System.currentTimeMillis());
        a.setExpireAt(System.currentTimeMillis() + 86_400_000L);
        accountMapper.insert(a);
        return no;
    }

    private String aLedger(String userNo, long points, long availableAt) {
        String no = "PL" + System.nanoTime() % 100_000_000L;
        PtsUserLedger l = new PtsUserLedger();
        l.setLedgerNo(no);
        l.setUserNo(userNo);
        l.setBizType("EARN");
        l.setPoints(points);
        l.setBalanceAfter(0L);
        l.setMarket("CN");
        l.setAvailableAt(availableAt);
        ledgerMapper.insert(l);
        return no;
    }
}
