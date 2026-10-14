package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.reportbridge.DailyReportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家报表（B 端报表清单 R1「近几日收入与订单量」）。
 *
 * <p><b>为什么另起一个 Controller 而不是塞进 BizDashboardController</b>：
 * 那一个是工作台（此刻要干什么），这一个是报表（过去几天怎么样），
 * 两者的数据来源完全不同 —— 工作台全是现算，报表读的是独立库里的日汇总。
 *
 * <p><b>权限复用 {@code BizPerms.CUSTOMER}</b>，与 {@code /biz/dashboard/stats} 同一档：
 * 它们是同一类经营数据。新造一个权限码要另走一整套登记，而这里没有新的授权语义。
 *
 * <p><b>整个 Controller 随 {@code shop.report.enabled} 一起装</b>：报表库没开的环境
 * 不该因为多了一个端点而在启动时缺 bean。
 */
@Profile("api")
@ConditionalOnProperty(prefix = "shop.report", name = "enabled", havingValue = "true")
@RestController
public class BizReportController {

    /** 允许的回看天数。**不接任意值** —— 3 个档足够，而任意值会让日结的窗口失去意义。 */
    private static final int[] ALLOWED_DAYS = {7, 14, 30};

    private final DailyReportService reports;

    public BizReportController(DailyReportService reports) {
        this.reports = reports;
    }

    /**
     * 近几日的逐日与合计（含环比）。
     *
     * <p>今天那一格是现算的，T-1 及以前读日汇总 —— 分界与理由见
     * {@link DailyReportService}。每一行带 {@code complete}：
     * 日结还没算到的那几天要标出来，**不能画成 0**。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/report/daily")
    public DailyReportService.DailyReport daily(@RequestParam(defaultValue = "7") int days) {
        BizContext ctx = BizContext.current();
        String merchantNo = BizContext.requireMerchantNo();
        return reports.recent(merchantNo, ctx.currentStoreScope(), normalize(days));
    }

    /**
     * 按月营收（R2）：逐月的单量与钱，默认回看 6 个月。
     *
     * <p><b>本月那一行多半是不全的</b> —— 日结只算到 T-1，今天的单还没进去。
     * 端上要按 {@code statsThrough} 提示，别让商家把半个月当成整月去比。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/report/monthly")
    public DailyReportService.MonthlyReport monthly(@RequestParam(defaultValue = "6") int months) {
        BizContext ctx = BizContext.current();
        String merchantNo = BizContext.requireMerchantNo();
        // 1~24 个月：再长的话一次要扫两年的日行，而商家看的是趋势不是账本
        int m = Math.min(Math.max(months, 1), 24);
        return reports.monthly(merchantNo, ctx.currentStoreScope(), m);
    }

    /**
     * 商品销售榜（R3）：近几天按件数或销售额排的前 N 个商品。
     *
     * <p><b>件数不含赠品</b>，赠出量单列 —— 混在一起的话「送出去 100 件」
     * 会被读成「卖了 100 件」，而那种失真不报错、只让决策变歪。
     *
     * <p><b>不含今天</b>：榜单看的是一段时间的趋势，少今天一天不改变结论，
     * 而「今天哪个商品卖得最好」要扫当天全部订单行，代价与收益不匹配。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/report/goods")
    public DailyReportService.GoodsRank goods(@RequestParam(defaultValue = "30") int days,
                                              @RequestParam(defaultValue = "qty") String orderBy,
                                              @RequestParam(defaultValue = "10") int limit) {
        BizContext ctx = BizContext.current();
        String merchantNo = BizContext.requireMerchantNo();
        return reports.goodsRank(merchantNo, ctx.currentStoreScope(), normalize(days), orderBy, limit);
    }

    /** 落到最近的合法档。传 9 给 7、传 999 给 30 —— 不报错，因为这不是用户填的字段。 */
    private static int normalize(int days) {
        int best = ALLOWED_DAYS[0];
        for (int d : ALLOWED_DAYS) {
            if (d <= days) {
                best = d;
            }
        }
        return best;
    }
}
