package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.entity.StlPointsPool;
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
 * 积分资金池入账。
 *
 * <p><b>此前池子只读不写</b>：{@code stl_points_pool} 与
 * {@code stl_bill.points_fee_minor} 全仓找不到任何写入点 ——
 * 预付费模型的账<b>一分钱都没记过</b>。
 *
 * <p>而这个缺口特别难发现：B 端「本期积分支出」永远是 0，
 * overview 的恒等式（流通积分 vs 池子余额）<b>两边都是 0，看着还挺平</b>。
 * 不是「数字不对」，是「根本没有数字」。
 *
 * <p>另一条：平台掏的钱在两条资金路径下<b>性质不同</b> ——
 * 直连是划进商家账户（对外付款），归集是平台自己少收（收入减项）。
 * 记成同一种的话，「平台给商家补了多少钱」会被归集的部分虚增。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("积分资金池：账要真的记，且按资金路径分流")
class PointsPoolFlowTest {

    @Autowired
    private PointsService pointsService;

    @Autowired
    private PointsPoolMapper poolMapper;

    @Test
    @DisplayName("★★ 补差入账 —— 直连路径记 MERCHANT_PAY，方向是出")
    void merchantPayIsOutflow() {
        String ref = "REF" + System.nanoTime() % 100_000_000L;

        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_PAY, 1000, "M1", ref, "WECHAT", "CN");

        StlPointsPool f = only(ref);
        assertThat(f.getPoolType()).isEqualTo(StlPointsPool.MERCHANT_PAY);
        assertThat(f.getDirection()).isEqualTo(StlPointsPool.OUT);
        assertThat(f.getAmountMinor()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("★★ 归集路径记 PLATFORM_ISSUE —— 与补差是两种钱，不能混")
    void aggregatedRecordsPlatformIssue() {
        String ref = "REF" + System.nanoTime() % 100_000_000L;

        pointsService.recordPoolFlow(StlPointsPool.PLATFORM_ISSUE, 800, "M2", ref, "WECHAT", "CN");

        StlPointsPool f = only(ref);
        assertThat(f.getPoolType()).isEqualTo(StlPointsPool.PLATFORM_ISSUE);
        // 也是出账，但类型不同 —— overview 里「补给商家的钱」只统计 MERCHANT_PAY，
        // 混记会让那个数被归集的部分虚增
        assertThat(f.getDirection()).isEqualTo(StlPointsPool.OUT);
    }

    @Test
    @DisplayName("★ 收发分服务费是进账")
    void merchantReceiveIsInflow() {
        String ref = "REF" + System.nanoTime() % 100_000_000L;

        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, 50, "M3", ref, "WECHAT", "CN");

        assertThat(only(ref).getDirection()).isEqualTo(StlPointsPool.IN);
    }

    @Test
    @DisplayName("★ 0 或负数不入账 —— 方向由类型决定，不靠符号表达")
    void nonPositiveIgnored() {
        String ref = "REF" + System.nanoTime() % 100_000_000L;

        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_PAY, 0, "M4", ref, "WECHAT", "CN");
        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_PAY, -5, "M4", ref, "WECHAT", "CN");

        assertThat(flows(ref)).isEmpty();
    }

    @Test
    @DisplayName("★ balance_after 落快照 —— 对不上时只有它能指出断点在哪一行")
    void snapshotsBalanceAfter() {
        String ch = "CH" + System.nanoTime() % 100_000_000L;
        String r1 = "R1" + System.nanoTime() % 100_000_000L;
        String r2 = "R2" + System.nanoTime() % 100_000_000L;

        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, 300, "M5", r1, ch, "CN");
        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_PAY, 100, "M5", r2, ch, "CN");

        assertThat(only(r1).getBalanceAfter()).isEqualTo(300L);
        assertThat(only(r2).getBalanceAfter()).isEqualTo(200L);
    }

    // ---------------------------------------------------------------- fixtures

    private List<StlPointsPool> flows(String refNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                poolMapper.selectList(Wrappers.<StlPointsPool>lambdaQuery()
                        .eq(StlPointsPool::getRefNo, refNo)));
    }

    private StlPointsPool only(String refNo) {
        List<StlPointsPool> rows = flows(refNo);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
