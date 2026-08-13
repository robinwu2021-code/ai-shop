package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.settle.PointsService;
import ai.neargo.shop.settle.entity.StlPointsPool;
import ai.neargo.shop.settle.mapper.SettleMappers.PointsPoolMapper;
import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发分费用金：<b>预付费模型的入账侧</b>。
 *
 * <p><b>此前这一侧整个不存在</b>，而它是恒等式 2（池子余额 == 流通中积分 × 汇率）
 * 的一半 —— 积分域-完整方案称它为「预付费模型的核心保证」。
 *
 * <p>缺了它之后果是单向的：用户花分时 {@code MERCHANT_PAY} 出池，
 * 而发分时<b>没有任何对应的入账</b> —— 池子只出不进，
 * <b>失衡量随发放量单调增长</b>。与此前 {@code EXPIRE_INCOME} 缺失时是同一个病，
 * 只是方向相反。
 *
 * <p>症状同样「看着挺平」：{@code stl_bill.points_fee_minor} 与
 * {@code ord_sub_order.points_fee_minor} <b>两张表实测都是 0 行有值</b>，
 * B 端「本期积分支出」永远显示 0 —— 不是数字不对，是根本没有数字。
 *
 * <p><b>费率 1:1，不打折不加价</b>：这些分将来可能被用户在<b>别家</b>花掉，
 * 那时平台要从池子里付给收单方。收得比将来要付的少，恒等式当场不成立。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("发分费用金：池子的入账侧，恒等式的另一半")
class PointsFeeFlowTest {

    @Autowired
    private PointsService pointsService;

    @Autowired
    private PointsPoolMapper poolMapper;

    @Test
    @DisplayName("★★★ SettleSource 要把费用金带出来 —— 结算域拿不到它就扣不了也入不了池")
    void settleSourceCarriesFee() {
        var src = new SettleSourcePort.SettleSource(
                "SUB-F", "M0001", "PLATFORM", 8_000L, 0L, 0L, null, 1, "ST001", 0L, 300L);

        assertThat(src.pointsFeeMinor()).isEqualTo(300L);
    }

    @Test
    @DisplayName("★★★ 抵扣与费用金是反方向的两笔 —— 同一张单上都可能有，收付方常常不是同一家")
    void feeAndDeductionAreOppositeDirections() {
        var src = new SettleSourcePort.SettleSource(
                "SUB-G", "M0001", "PLATFORM", 8_000L, 0L, 0L, null, 1, "ST001",
                2_000L, 300L);

        // 抵扣：平台**付给**收单商家（出池）；费用金：平台**向发放商家收**（入池）。
        // 并成一个字段的话，一张既发分又收分的单子会把两笔抵消掉，
        // 而抵消后的净额在任何一本账上都对不上
        assertThat(src.pointsDeductMinor()).isEqualTo(2_000L);
        assertThat(src.pointsFeeMinor()).isEqualTo(300L);
    }

    @Test
    @DisplayName("★★★ MERCHANT_RECEIVE 是入账方向 —— 记成出账，恒等式会朝反方向失衡")
    void feeIsInflow() {
        String ref = "REFF" + System.nanoTime() % 100_000_000L;

        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, 300L,
                "M0001", ref, "WECHAT", "CN");

        List<StlPointsPool> flows = poolOf(ref);
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).getDirection()).isEqualTo(StlPointsPool.IN);
    }

    @Test
    @DisplayName("★★ 发分返回的费用金 = 分数对应的钱，1:1")
    void feeEqualsMoneyValueOfPoints() {
        var g = new PointsPort.GrantResult(500L, 500L);

        // 汇率是 perMinor=1（100 分抵 1 元 → 1 分 = 1 分钱），所以 500 分 = 500 分钱。
        // 打折收的话，池子收的比将来要付的少 —— 恒等式当场不成立
        assertThat(g.feeMinor()).isEqualTo(g.points());
    }

    @Test
    @DisplayName("★ 没发分就没有费用金 —— 不能记一笔 0 元入池")
    void noGrantNoFee() {
        assertThat(PointsPort.GrantResult.none().feeMinor()).isZero();

        String ref = "REFF0" + System.nanoTime() % 100_000_000L;
        pointsService.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, 0L,
                "M0001", ref, "WECHAT", "CN");
        // 0 元流水是噪音：对账时每一行都要有人解释，而这一行解释不出任何事
        assertThat(poolOf(ref)).isEmpty();
    }

    private List<StlPointsPool> poolOf(String refNo) {
        return DataScopeContext.executeWithoutScope(() ->
                poolMapper.selectList(Wrappers.<StlPointsPool>lambdaQuery()
                        .eq(StlPointsPool::getRefNo, refNo)));
    }
}
