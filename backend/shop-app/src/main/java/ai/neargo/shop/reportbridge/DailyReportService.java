package ai.neargo.shop.reportbridge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.neargo.shop.report.dao.ReportDailyStoreDao;
import ai.neargo.shop.report.dao.ReportWatermarkDao;
import ai.neargo.shop.report.dto.DailyStoreRow;
import ai.neargo.shop.report.dto.Watermark;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 「近几日」报表的读侧（TDD-B端报表库与日结 §2.4）。
 *
 * <p><b>今天与历史来自两个地方</b>：
 * <ul>
 *   <li><b>今天</b>走现算（{@code MerchantOrderService.stats}）—— 今天的数还在变，
 *       落表就会比实际少，而日结要到明天凌晨才跑到它；</li>
 *   <li><b>T-1 及以前</b>读 {@code rpt_daily_store}。</li>
 * </ul>
 * 不写清这条分界的后果很具体：「近 7 天」里今天那一格恒为 0，而界面上看不出为什么。
 *
 * <p><b>缺口要标出来，不能当成 0。</b>日结没跑到的那几天，
 * 在报表里的表现与「那天真的没单」一模一样 —— 靠 {@code rpt_job_watermark} 区分，
 * 落到每一行的 {@code complete} 上。
 */
@ConditionalOnProperty(prefix = "shop.report", name = "enabled", havingValue = "true")
@Service
public class DailyReportService {

    private final ReportDailyStoreDao dailyStoreDao;
    private final ReportWatermarkDao watermarkDao;
    private final MerchantOrderService orders;

    public DailyReportService(ReportDailyStoreDao dailyStoreDao,
                              ReportWatermarkDao watermarkDao,
                              MerchantOrderService orders) {
        this.dailyStoreDao = dailyStoreDao;
        this.watermarkDao = watermarkDao;
        this.orders = orders;
    }

    /**
     * 近 {@code days} 天（含今天）的逐日与合计，并带上一个等长区间的合计用于环比。
     *
     * @param storeNos 门店范围；{@code null} 表示该商户全部门店
     */
    public DailyReport recent(String merchantNo, Collection<String> storeNos, int days) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        LocalDate yesterday = today.minusDays(1);

        // 本期：[from, 昨天] 读汇总 + 今天现算
        Map<LocalDate, DailyStoreRow> rolled = byDate(
                from.isAfter(yesterday) ? List.of()
                        : dailyStoreDao.findRange(merchantNo, storeNos, from, yesterday));

        LocalDate through = watermarkDao.find(ReportDailyRollupJob.WATERMARK_KEY)
                .map(Watermark::lastStatDate).orElse(null);

        List<DailyRow> rows = new ArrayList<>();
        for (LocalDate d = today; !d.isBefore(from); d = d.minusDays(1)) {
            if (d.equals(today)) {
                var s = orders.stats(merchantNo, storeNos);
                // 今天永远是「完整」的：它来自现算，不依赖日结
                rows.add(new DailyRow(d.toString(), s.todayOrders(), s.todayGmvMinor(), 0, 0L, true));
                continue;
            }
            DailyStoreRow r = rolled.get(d);
            // 日结还没算到这一天 → complete=false。**不是 0，是「还不知道」**
            boolean complete = through != null && !through.isBefore(d);
            rows.add(new DailyRow(d.toString(),
                    r == null ? 0 : r.orders(),
                    r == null ? 0L : r.gmvMinor(),
                    r == null ? 0 : r.refundOrders(),
                    r == null ? 0L : r.refundMinor(),
                    complete));
        }

        // 上一个等长区间，整段都在汇总里
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1L);
        List<DailyStoreRow> prev = dailyStoreDao.findRange(merchantNo, storeNos, prevFrom, prevTo);

        return new DailyReport(days, "CNY",
                rows.stream().mapToInt(DailyRow::orders).sum(),
                rows.stream().mapToLong(DailyRow::gmvMinor).sum(),
                prev.stream().mapToInt(DailyStoreRow::orders).sum(),
                prev.stream().mapToLong(DailyStoreRow::gmvMinor).sum(),
                through == null ? null : through.toString(),
                rows);
    }

    /** 同一天多家店时按天合并 —— 查询层决定合不合并，表里不落合计行。 */
    private static Map<LocalDate, DailyStoreRow> byDate(List<DailyStoreRow> rows) {
        return rows.stream().collect(Collectors.toMap(DailyStoreRow::statDate,
                Function.identity(),
                (a, b) -> new DailyStoreRow(a.statDate(), a.entityNo(), "*",
                        a.orders() + b.orders(), a.gmvMinor() + b.gmvMinor(),
                        a.refundOrders() + b.refundOrders(), a.refundMinor() + b.refundMinor(),
                        a.buyers() + b.buyers(), a.newBuyers() + b.newBuyers(),
                        a.ownedOrders() + b.ownedOrders(), a.ownedGmvMinor() + b.ownedGmvMinor(),
                        a.attributedOrders() + b.attributedOrders(),
                        a.commissionMinor() + b.commissionMinor(),
                        a.serviceFeeMinor() + b.serviceFeeMinor(),
                        a.freightIncomeMinor() + b.freightIncomeMinor(),
                        a.netMinor() + b.netMinor(), a.currency())));
    }

    /**
     * <b>日期一律是 {@code yyyy-MM-dd} 的字符串，不是 {@code LocalDate}。</b>
     * 本仓库 wire 上的日期都是字符串（{@code arriveDate} 10 处、{@code period}），
     * 而 {@code LocalDate} 落到 JSON 上长什么样取决于 Jackson 有没有关
     * {@code WRITE_DATES_AS_TIMESTAMPS} —— 这个仓库没有显式配置，
     * 不该赌默认行为（赌错了是 {@code [2026,9,30]}，端上按字符串解析会静默拿到空）。
     *
     * @param statsThrough 日结算到哪一天；{@code null} 表示从没跑过。
     *                     端上据它提示「统计中」，而不是把缺口画成 0
     */
    public record DailyReport(int days, String currency,
                              int totalOrders, long totalGmvMinor,
                              int prevOrders, long prevGmvMinor,
                              String statsThrough,
                              List<DailyRow> rows) {
    }

    /**
     * @param date     {@code yyyy-MM-dd}
     * @param complete 这一天的数是不是齐的。{@code false} 表示日结还没算到它 ——
     *                 **与「那天没单」不是一回事**，端上要区分显示
     */
    public record DailyRow(String date, int orders, long gmvMinor,
                           int refundOrders, long refundMinor, boolean complete) {
    }

    /** 给测试用：让水位可读，不必起整个作业。 */
    Optional<Watermark> watermark() {
        return watermarkDao.find(ReportDailyRollupJob.WATERMARK_KEY);
    }
}
