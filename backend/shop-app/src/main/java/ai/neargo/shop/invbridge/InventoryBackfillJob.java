package ai.neargo.shop.invbridge;

import ai.neargo.shop.invbridge.InventoryBackfillService.Report;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 存量搬运任务。
 *
 * <p><b>默认只算不写</b>（{@code shop.inventory.backfill.dry-run=true}）。
 * 先让它跑几轮、把报告看干净了，再把这一位翻过来 ——
 * 这是唯一一次不可回退的数据动作，不该由「默认值恰好是 true」来决定。
 *
 * <p><b>为什么是任务不是端点</b>：迁移由一次 HTTP 调用触发的话，
 * 谁点的、点了几次、点到哪一批全都要另外记；而任务有 {@code sys_job_run}、
 * 有 ShedLock 防重入、有 cron 可以停 —— 这些本来就有。
 */
@Profile("worker")
@Component
@ConditionalOnProperty(prefix = "shop.inventory.backfill", name = "enabled", havingValue = "true")
public class InventoryBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryBackfillJob.class);

    private final InventoryBackfillService backfill;
    private final JobSupport jobs;

    @Value("${shop.inventory.backfill.dry-run:true}")
    private boolean dryRun;

    @Value("${shop.inventory.backfill.batch:500}")
    private int batch;

    public InventoryBackfillJob(InventoryBackfillService backfill, JobSupport jobs) {
        this.backfill = backfill;
        this.jobs = jobs;
    }

    /**
     * 上一轮扫到哪儿了。
     *
     * <p><b>它必须存在</b>：没有游标时每一轮都从第一行扫同样一批，第二轮起全是
     * 「已搬过」，报告长得和「搬完了」一模一样，而第 {@code batch+1} 个 SKU 永远搬不到。
     *
     * <p>只放在内存里：进程重启就从头再扫一遍 —— 慢，但**不会漏**（已搬的会被跳过）。
     * 落库要新开一张表与一次迁移，而这件事本身是一次性的，不值得。
     */
    private Long cursor;

    /** 默认每十分钟一批。**扫到末尾就从头再来** —— 这样新建的 SKU 下一圈会被带上。 */
    @Scheduled(cron = "${shop.inventory.backfill.cron:0 */10 * * * *}")
    @SchedulerLock(name = "inv-backfill", lockAtLeastFor = "PT30S", lockAtMostFor = "PT30M")
    public void run() {
        jobs.run("inv-backfill", () -> {
            Report r = backfill.run(dryRun, batch, cursor);
            cursor = r.nextAfterId();   // null = 扫完了，下一轮从头开始
            if (!r.clean()) {
                // 对差不为零、或还有没搬的，都要看得见 —— 它是 G3 闸门拦下来的那个数
                log.warn("库存搬运：对差 {} 条、待搬 {} 个，**不得切换真相源**；前三条差异：{}",
                        r.diffs().size(), r.pending(), r.diffs().stream().limit(3).toList());
            }
            return "scanned=" + r.scannedSkus() + " moved=" + r.moved()
                    + " skipped=" + r.skipped() + " pending=" + r.pending()
                    + " diffs=" + r.diffs().size()
                    + (r.nextAfterId() == null ? " (到末尾)" : " (下一轮 after=" + r.nextAfterId() + ")")
                    + (dryRun ? " (dry-run)" : "");
        });
    }
}
