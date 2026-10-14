package ai.neargo.shop.scenario;

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
 * 到期清零 —— <b>恒等式成立的前提</b>。
 *
 * <p>池子的恒等式是「流通中的积分 == 池子里的钱」。用户的分过期了却不清零：
 * 流通侧不减，池子侧也不记 {@code EXPIRE_INCOME} ——
 * <b>池子只增不减，失衡量随时间单调增长</b>。
 *
 * <p>而这个任务此前<b>整个不存在</b>。滚动到期的写侧（每次变动推后 expire_at）
 * 一直是对的，只是没有人在到期那天来收。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("积分到期清零：不清的话池子只增不减")
class PointsExpireFlowTest {

    @Autowired
    private PointsService pointsService;

    @Autowired
    private PointsAccountMapper accountMapper;

    @Autowired
    private PointsLedgerMapper ledgerMapper;

    @Autowired
    private PointsPoolMapper poolMapper;

    @Test
    @DisplayName("★★ 到期账户余额清空，并记一条负数流水 —— 钱去哪了要说得出")
    void expiredAccountCleared() {
        String user = anAccount(500, System.currentTimeMillis() - 1000);

        pointsService.expireIdleAccounts();

        assertThat(account(user).getBalance()).isZero();
        List<PtsUserLedger> rows = ledgers(user);
        assertThat(rows).hasSize(1);
        // points 带符号：EXPIRE 记负数，与 USE/REVOKE 同一约定
        assertThat(rows.get(0).getPoints()).isEqualTo(-500L);
        assertThat(rows.get(0).getBizType()).isEqualTo(PtsUserLedger.EXPIRE);
    }

    @Test
    @DisplayName("★★ 池子同步记 EXPIRE_INCOME —— 这是这个类型存在的全部理由")
    void poolRecordsExpireIncome() {
        String user = anAccount(300, System.currentTimeMillis() - 1000);

        pointsService.expireIdleAccounts();

        String ledgerNo = ledgers(user).get(0).getLedgerNo();
        List<StlPointsPool> flows = ai.neargo.common.data.scope.DataScopeContext
                .executeWithoutScope(() -> poolMapper.selectList(
                        Wrappers.<StlPointsPool>lambdaQuery().eq(StlPointsPool::getRefNo, ledgerNo)));
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).getPoolType()).isEqualTo(StlPointsPool.EXPIRE_INCOME);
        assertThat(flows.get(0).getAmountMinor()).isEqualTo(300L);
    }

    @Test
    @DisplayName("★★ 没到期的不动 —— 滚动到期意味着「这些天他一直有动静」")
    void notExpiredUntouched() {
        String user = anAccount(200, System.currentTimeMillis() + 86_400_000L);

        pointsService.expireIdleAccounts();

        assertThat(account(user).getBalance()).isEqualTo(200L);
        assertThat(ledgers(user)).isEmpty();
    }

    @Test
    @DisplayName("★ 已经是 0 的不重复清 —— 否则每天为同一批空账户写一条 0 分流水")
    void zeroBalanceSkipped() {
        String user = anAccount(0, System.currentTimeMillis() - 1000);

        pointsService.expireIdleAccounts();

        assertThat(ledgers(user)).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private PtsUserAccount account(String userNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                accountMapper.selectOne(Wrappers.<PtsUserAccount>lambdaQuery()
                        .eq(PtsUserAccount::getUserNo, userNo).last("LIMIT 1")));
    }

    private List<PtsUserLedger> ledgers(String userNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getUserNo, userNo)));
    }

    private String anAccount(long balance, long expireAt) {
        String no = "PX" + System.nanoTime() % 100_000_000L;
        PtsUserAccount a = new PtsUserAccount();
        a.setUserNo(no);
        a.setMarket("CN");
        a.setBalance(balance);
        a.setPendingBalance(0L);
        a.setTotalEarn(balance);
        a.setLastActiveAt(System.currentTimeMillis());
        a.setExpireAt(expireAt);
        accountMapper.insert(a);
        return no;
    }
}
