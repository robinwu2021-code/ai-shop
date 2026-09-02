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

    /** 漏斗前两环的统计窗口：与获客看板的缺省窗口一致（30 天） */
    private static final long FUNNEL_WINDOW_MS = 30L * 24 * 3600 * 1000;

    private final TradeStatsPort tradeStats;
    private final MerchantApplyMapper applyMapper;
    /** 排行要显示商家名 —— platform 与 merchant 是兄弟模块，只能走 Port */
    private final ai.neargo.shop.spi.user.MerchantQueryPort merchantPort;
    /**
     * 漏斗前两环。**读它不自己算** —— 两处各算一份就会有两个「扫码数」。
     *
     * <p>走 spi 的 Port 而不是直接注入 marketing 域的 Service：
     * platform 与 marketing 是两个域，直接依赖会让它们长在一起
     * （ArchitectureTest.svcModulesMustNotDependOnEachOther 拦的就是这个）。
     */
    private final ai.neargo.shop.spi.marketing.StoreVisitQueryPort storeVisitPort;
    /** 待审商品积压。走 SPI 而不是直接引 product 的 mapper —— 那是域间直连 */
    private final ai.neargo.shop.spi.product.GoodsQueryPort goodsQuery;

    public DashboardServiceImpl(TradeStatsPort tradeStats, MerchantApplyMapper applyMapper,
                                ai.neargo.shop.spi.user.MerchantQueryPort merchantPort,
                                ai.neargo.shop.spi.marketing.StoreVisitQueryPort storeVisitPort,
                                ai.neargo.shop.spi.product.GoodsQueryPort goodsQuery) {
        this.tradeStats = tradeStats;
        this.applyMapper = applyMapper;
        this.merchantPort = merchantPort;
        this.storeVisitPort = storeVisitPort;
        this.goodsQuery = goodsQuery;
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

        /*
         * 待审商品：**数量与最久等待一起给**。看板上此前没有这一格，而运营端
         * 有「商品审核队列」那个入口 —— 入口是要人主动点进去的，
         * 它不会说「有 194 件在等你」。2026-09-03 线上待审 194 件，最早那件已等了两周上下。
         */
        ai.neargo.shop.spi.product.GoodsQueryPort.AuditBacklog backlog = goodsQuery.auditBacklog();

        return new KpiVO(t.gmv(), t.orderCount(), avg, pendingAudit,
                tradeStats.openAfterSaleCount(), tradeStats.todayRedeemRate(),
                backlog.pending(), backlog.oldestDays());
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
         * 「扫码 → 进店 → 注册 → 首单」四环。
         *
         * 前两环此前给不出来：平台没有任何扫码/进店的事件表（mkt_attribution_log 记的是
         * 归因决策，不是曝光事件），当时的取舍是**宁可少两行也不返回 0** ——
         * 0 会被读成「一个人都没扫码」，而运营会照着它去判断投放效果。
         *
         * V290 的 mkt_store_visit 补上了埋点，所以这两环现在是真的了。
         *
         * ★ **必须与获客看板同一个口径**：两处各写一份 group by 的话，
         * 首页漏斗和门店获客看板会给出两个不一样的「扫码数」，而两个都看起来是对的。
         * 所以这里读埋点域的 Port，不自己算（TDD-门店获客埋点与看板 §3.3）。
         */
        long to = System.currentTimeMillis();
        var f = storeVisitPort.platformFunnel(to - FUNNEL_WINDOW_MS, to);
        TradeStatsPort.Reach reach = tradeStats.reach();
        return List.of(new FunnelRowVO("SCAN", f.scanUv()),
                new FunnelRowVO("ENTER", f.enter()),
                new FunnelRowVO("REGISTER", reach.ordered()),
                new FunnelRowVO("FIRST_ORDER", reach.paid()));
    }

    @Override
    public List<MerchantRankRowVO> merchantRanking(int days, int limit) {
        int span = Math.min(Math.max(days, 1), 90);
        int top = Math.min(Math.max(limit, 1), 100);
        LocalDate from = LocalDate.now().minusDays(span - 1L);

        List<TradeStatsPort.MerchantTotal> totals = tradeStats.merchantTotals(from);
        if (totals.isEmpty()) {
            return List.of();
        }
        List<TradeStatsPort.MerchantTotal> head = totals.size() > top ? totals.subList(0, top) : totals;

        /*
         * 商家名批量取回来 —— 逐行查是 N+1，而这是首屏的一部分。
         * 走 Port 不直连 merchant 域的表：platform 与 merchant 是兄弟模块。
         */
        Map<String, ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief> briefs =
                merchantPort.findAll(head.stream()
                        .map(TradeStatsPort.MerchantTotal::merchantNo)
                        .collect(java.util.stream.Collectors.toSet()));

        return head.stream().map(t -> {
            var brief = briefs.get(t.merchantNo());
            // 无单时客单价 0 而不是除零 —— 与 kpi() 同一口径
            long avg = t.orderCount() == 0 ? 0L : t.gmv() / t.orderCount();
            /*
             * 分母是**总成交单数**（在售的 + 已退的），不是只有在售的。
             * 只用在售的话，一家单子全退光的商家分母为 0、率显示 0% ——
             * 而它恰恰是这一列要揪出来的那家。
             */
            long sold = t.orderCount() + t.refundedCount();
            double rate = sold == 0 ? 0d : (double) t.afterSaleCount() / sold;
            return new MerchantRankRowVO(t.merchantNo(),
                    brief == null ? t.merchantNo() : brief.merchantName(),
                    t.gmv(), t.orderCount(), avg, t.afterSaleCount(), rate);
        }).toList();
    }
}
