package ai.neargo.shop.reportbridge;

import java.time.LocalDate;
import java.util.List;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.report.config.ReportStoreProperties;
import ai.neargo.shop.report.dao.ReportDailyStoreDao;
import ai.neargo.shop.report.dao.ReportWatermarkDao;
import ai.neargo.shop.report.dto.DailyStoreRow;
import ai.neargo.shop.trade.service.MerchantOrderService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 门店日汇总的日结（TDD-B端报表库与日结 §2.3）。
 *
 * <p><b>为什么住在 shop-app 而不是 shop-report</b>：它读交易域、写报表库，
 * 是**跨域组合**。ArchitectureTest 的原话是「跨域组合必须有地方待，app 层就是那个地方」。
 * 反过来让 shop-report 依赖 shop-core，报表就成了交易域的下游，拆的时候要连着搬。
 *
 * <p><b>两个开关都要开</b>：{@code shop.job.enabled} 决定跑不跑定时任务，
 * {@code shop.report.enabled} 决定报表库装不装。只挂前一个的话，
 * 开了 job 没开 report 的环境会因为缺 DAO 而**起不来** —— 而那是一台本来好好的机器。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "shop.report", name = "enabled", havingValue = "true")
@Component
public class ReportDailyRollupJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportDailyRollupJob.class);

    private static final String NAME = "report-daily-rollup";

    /** 水位的键。读侧靠它判断「昨天到底算过没有」。 */
    public static final String WATERMARK_KEY = "daily-store";

    private final MerchantOrderService orders;
    private final ReportDailyStoreDao dailyStoreDao;
    private final ReportWatermarkDao watermarkDao;
    private final ReportStoreProperties props;
    private final JobSupport jobs;

    public ReportDailyRollupJob(MerchantOrderService orders,
                                ReportDailyStoreDao dailyStoreDao,
                                ReportWatermarkDao watermarkDao,
                                ReportStoreProperties props,
                                JobSupport jobs) {
        this.orders = orders;
        this.dailyStoreDao = dailyStoreDao;
        this.watermarkDao = watermarkDao;
        this.props = props;
        this.jobs = jobs;
    }

    /**
     * 01:30 跑。**锁最长 2 小时、超时 100 分钟** —— 锁必须比超时长，
     * 否则调用还没结束锁就放了，第二个实例会同时跑一遍。
     */
    @Scheduled(cron = "${shop.job.report-daily-rollup.cron:0 30 1 * * *}")
    @SchedulerLock(name = NAME, lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    public void rollup() {
        jobs.run(NAME, () -> run(null).detail());
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 重算 {@code [T-N, T-1]} 这个窗口，**先删后写**。
     *
     * <p><b>不是增量累加</b>：退款、售后、改价都会改动历史某一天的数字，
     * 增量的话前天的一笔退款永远补不回去，而且不会有任何东西报错。
     * 重算窗口让「最近几天」始终是对的，代价只是每天多算 N 天的量。
     *
     * <p><b>今天（T）不算</b>：今天的数还在变，落表就会比实际少；读侧对「今天」
     * 走现算那条路（TDD §2.4）。
     */
    @Override
    public JobResult run(JobInvocation invocation) {
        long started = System.nanoTime();
        LocalDate to = LocalDate.now().minusDays(1);
        LocalDate from = to.minusDays(Math.max(0, props.getRollbackDays() - 1));
        try {
            List<MerchantOrderService.DailyAgg> aggs = orders.dailyStoreAggregates(from, to);
            List<DailyStoreRow> rows = aggs.stream().map(ReportDailyRollupJob::toRow).toList();
            int written = dailyStoreDao.replaceWindow(from, to, rows);
            long ms = (System.nanoTime() - started) / 1_000_000;
            // 跑成功了才推水位 —— 水位的含义是「算到这儿了」，不是「试过了」
            watermarkDao.advance(WATERMARK_KEY, to, written, ms);
            String detail = "窗口 " + from + " → " + to + "，写入 " + written + " 行，耗时 " + ms + " ms";
            log.info("[report-daily-rollup] {}", detail);
            return JobResult.ok(detail);
        } catch (RuntimeException e) {
            // 不抛出去：抛异常会变成 HTTP 错误，worker 只能记成「调不通」，
            // 而那与「跑了但失败了」在排查时是两件事（JobHandler 的约定二）
            log.error("[report-daily-rollup] 窗口 {} → {} 失败", from, to, e);
            return JobResult.failed("窗口 " + from + " → " + to + " 失败：" + e.getMessage(),
                    e.getClass().getSimpleName());
        }
    }

    private static DailyStoreRow toRow(MerchantOrderService.DailyAgg a) {
        return new DailyStoreRow(a.statDate(), a.entityNo(), a.storeNo(),
                a.orders(), a.gmvMinor(), a.refundOrders(), a.refundMinor(),
                a.buyers(), a.newBuyers(), a.ownedOrders(), a.ownedGmvMinor(),
                a.attributedOrders(), a.commissionMinor(), a.serviceFeeMinor(),
                a.freightIncomeMinor(), a.netMinor(),
                // 币种跟着商户走，本期只有人民币；多币种时从子单上带出来
                "CNY");
    }

    @Bean
    public JobDeclaration reportdailyrollupDeclaration() {
        return new JobDeclaration(NAME, "报表日结 · 门店日汇总",
                "重算最近几天的门店日汇总（rpt_daily_store）。不跑的话「近几日」报表会停在上次跑的那天，"
                + "而少一行与「那天真的没单」在界面上长得一模一样 —— 靠 rpt_job_watermark 区分。",
                "shop-app", "0 30 1 * * *", true,
                // 超时 100 分钟、锁最长 120 分钟：锁必须比超时长，否则锁先放了会有第二个实例同时跑
                6000, 7200,
                true,
                false);
    }
}
