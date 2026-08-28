package ai.neargo.shop.invbridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天把平台侧与进销存侧的库存**逐条对一遍**，只读，不搬不改。
 *
 * <p><b>为什么要有它：D2 的判据没人在攒。</b>
 * 分期表（TDD-进销存领域模型 §十）写着 D2「真相源切换」的判据是
 * <b>「对差连续 N 天为零才切」</b>，而 2026-08-28 核查发现：攒这个证据的
 * {@code inv-backfill} 在生产上<b>无处运行</b> —— 它挂在
 * {@code shop.inventory.backfill.enabled} 上，生产 env 里没有这个开关，
 * 主应用里连 bean 都没装配；它此前只在一个手工起的搬运 worker 里跑过，
 * 那个进程已随搬运完成而停掉。
 *
 * <p>搬运那一半确实该退休（209 个 SKU 已全部建成物料，新 SKU 改走
 * {@code SKU_UPSERTED} → outbox → 投影 这条常驻链路），<b>但对差是被它连坐停掉的</b>。
 * 后果不是当天出事，而是<b>「连续 N 天为零」的计数器从来没有开始计</b> ——
 * 等到真要切的那天临时跑一次，那不是证据，那是抽查。
 *
 * <p><b>刻意不复用 {@code inv-backfill}</b>：它的 {@code run()} 是搬运 + 对差同一个方法，
 * 会走写路径。而这件事只需要读 —— 一个只读任务出问题最多是「没看」，
 * 一个会写的任务出问题是「改坏了库存」，两者的最坏后果差着量级。
 * {@link InventoryBackfillService#diffOnly} 内部走的是 {@code doRun(dryRun=true, ...)}。
 *
 * <p><b>扫不完就判失败，不给「看着挺干净」。</b>
 * {@code diffOnly} 翻不完时会把 {@code clean} 强制为 false 并打 WARN，
 * 这里必须把它变成任务失败 —— 否则运营页面上是一行绿色的「成功」，
 * 而它代表的是「看过的那些没问题」，读的人会当成「没问题」。
 * 一道只抽样的闸门比没有闸门更坏。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class InventoryReconJob implements JobHandler {

    /**
     * 一次最多扫多少个 SKU。
     *
     * <p>给得比现有量级大一截（2026-08-28 平台侧 209 个）：扫不完是**失败**，
     * 所以这个数宁可偏大。真到了扫不完的规模，该做的是分片，不是把闸门调松。
     */
    private static final int MAX_SCAN = 20000;

    private final InventoryBackfillService backfill;
    private final JobSupport jobs;

    public InventoryReconJob(InventoryBackfillService backfill, JobSupport jobs) {
        this.backfill = backfill;
        this.jobs = jobs;
    }

    /** 每天 03:40 —— 避开 03:00–03:30 那一批扫描任务，别和它们抢库。 */
    @Scheduled(cron = "${shop.job.inv-recon.cron:0 40 3 * * *}")
    @SchedulerLock(name = "inv-recon", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void scan() {
        jobs.run("inv-recon", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "inv-recon";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration invreconDeclaration() {
        return JobDeclaration.daily("inv-recon", "库存对差（只读）",
                "逐条比平台侧与进销存侧的库存数。它攒的是切换真相源的唯一判据"
                        + "——连续 N 天为零才准切，所以每天都要跑，且扫不完算失败",
                "shop-app", "0 40 3 * * *");
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        InventoryBackfillService.Report r = backfill.diffOnly(MAX_SCAN);

        if (!r.clean() && r.nextAfterId() != null) {
            // 没翻到底：结论不成立。**说清楚是「没看完」而不是「看到了差异」**
            return JobResult.failed(
                    "对差未扫完：已扫 %d 个 SKU（上限 %d），本次结论不得当作切换判据"
                            .formatted(r.scannedSkus(), MAX_SCAN),
                    "SCAN_INCOMPLETE");
        }

        if (!r.clean()) {
            /*
             * clean=false 有两种原因，运营看报告时要分得开：
             * 差异（两边数不一样）与待搬（进销存里根本还没这个物料）。
             * 后者不会出现在 diffs 里 —— 见 Report 的构造器注释。
             */
            return JobResult.failed(
                    "对差不为零：扫描 %d，差异 %d 条，待搬 %d 个 —— 不得切换真相源"
                            .formatted(r.scannedSkus(), r.diffs().size(), r.pending()),
                    "DIFF_NOT_CLEAN");
        }

        return JobResult.ok("对差为零：扫描 %d 个 SKU，差异 0、待搬 0".formatted(r.scannedSkus()));
    }
}
