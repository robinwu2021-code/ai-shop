package ai.neargo.shop.platform.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.DashboardService;
import ai.neargo.shop.platform.entity.MchEntityApply;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import ai.neargo.shop.spi.trade.TradeStatsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营工作台的三个数（P-16.1）。
 *
 * <p>三个接口此前<b>全是 404</b> —— ops-web 的契约、类型、页面都写好了，缺的是后端。
 *
 * <p><b>没有一个数字是编的。</b> 算不出来的宁可不给：
 * 运营会照着这些数去催审、去催核销、去调预算，一个看起来像真的假数字
 * 比一个空白危险得多。
 *
 * <p>交易侧的聚合走 {@link TradeStatsPort} 而不是直连 trade 的 mapper ——
 * 那是跨域直连，ArchUnit 第 1 条当场拦下（实测撞到过）。
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private final TradeStatsPort tradeStats;
    private final MerchantApplyMapper applyMapper;

    public DashboardServiceImpl(TradeStatsPort tradeStats, MerchantApplyMapper applyMapper) {
        this.tradeStats = tradeStats;
        this.applyMapper = applyMapper;
    }

    @Override
    public KpiVO kpi() {
        TradeStatsPort.Totals t = tradeStats.paidTotals(null);
        // 无单时客单价是 0，不是除零。看板上的 0 是「还没开张」，异常是「打不开」
        long avg = t.orderCount() == 0 ? 0L : t.gmv() / t.orderCount();

        long pendingAudit = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectCount(Wrappers.<MchEntityApply>lambdaQuery()
                        .in(MchEntityApply::getStatus,
                                List.of(MchEntityApply.PENDING, MchEntityApply.REVIEWING))));

        return new KpiVO(t.gmv(), t.orderCount(), avg, pendingAudit,
                tradeStats.openAfterSaleCount(), tradeStats.todayRedeemRate());
    }

    @Override
    public List<TrendPointVO> trend(int days) {
        int span = Math.min(Math.max(days, 1), 90);
        LocalDate from = LocalDate.now().minusDays(span - 1L);

        /*
         * 先把每一天都放进去、值为 0，再往上填。
         *
         * 不这么做的话，无单的日子会整天缺失，折线把两个不相邻的日子直接连起来 ——
         * 看上去像「一直在涨」，而中间其实是断的。运营据此判断趋势，
         * 这种「好看的假象」比缺数据更坏。
         *
         * 补齐放在这一层而不是 Port 里：只有这里知道要展示几天。
         */
        Map<String, long[]> byDay = new LinkedHashMap<>();
        for (int i = 0; i < span; i++) {
            byDay.put(from.plusDays(i).toString(), new long[]{0L, 0L});
        }
        for (TradeStatsPort.DailyTotal d : tradeStats.dailyTotals(from)) {
            long[] cell = byDay.get(d.date());
            if (cell != null) {
                cell[0] = d.gmv();
                cell[1] = d.orderCount();
            }
        }
        List<TrendPointVO> out = new ArrayList<>(byDay.size());
        byDay.forEach((date, cell) -> out.add(new TrendPointVO(date, cell[0], cell[1])));
        return out;
    }

    @Override
    public List<FunnelRowVO> funnel() {
        /*
         * 设计上是「扫码 → 进店 → 注册 → 首单」四环，这里**只给后两环**。
         *
         * 前两环需要埋点，而平台没有任何扫码/进店的事件表（mkt_attribution_log 记的是
         * 归因决策，不是曝光事件）。返回 0 会被读成「一个人都没扫码」——
         * 那是假的，而运营会照着它去判断投放效果。少两行至少是真的。
         */
        TradeStatsPort.Reach reach = tradeStats.reach();
        return List.of(new FunnelRowVO("REGISTER", reach.ordered()),
                new FunnelRowVO("FIRST_ORDER", reach.paid()));
    }
}
