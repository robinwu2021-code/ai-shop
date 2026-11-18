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
 * 每天归档一批「来源 SKU 已经没了、且库存为 0」的空壳物料。
 *
 * <p>治的是一个查得到的症状：店主在商品库里一件货都没有，
 * 进货页的挑货弹层却列出两百多件（2026-09-18 线上 {@code prd_goods} 1 条
 * vs {@code inv_item} 211 条）。判据与理由见 {@link InventoryOrphanSweepService}。
 *
 * <p><b>为什么是每天而不是一次性跑批</b>：删商品这件事没有尽头。跑一次清完存量，
 * 下一个店主删掉一个 SKU 又长一条 —— 而它没有任何症状，只会慢慢把挑货弹层变脏。
 *
 * <p><b>为什么归档不算失败</b>：与 {@code inv-recon} 相反，这个任务归档了多少件
 * 都是正常的（那正是它的工作）。会让它失败的只有「没扫完」——
 * 那时报告里的几个数只是「看过的那些」，而读的人会当成全量。
 *
 * <p>两个开关都要：它依赖的实现挂在 {@code shop.inventory.enabled} 上，
 * 只声明 job 那一个会让 inventory 关着时整个应用起不来（见 {@link InventoryReconJob}）。
 */
@Component
@ConditionalOnProperty(name = { "shop.job.enabled", "shop.inventory.enabled" }, havingValue = "true")
public class InventoryOrphanSweepJob implements JobHandler {

    /**
     * 一次最多查多少条引用。
     *
     * <p>给得比现有量级大一截（2026-09-18 线上 212 条）：扫不完是失败，
     * 所以这个数宁可偏大。真到了扫不完的规模该做分片，不是把闸门调松。
     */
    private static final int MAX_SCAN = 20000;

    private final InventoryOrphanSweepService sweep;
    private final JobSupport jobs;

    public InventoryOrphanSweepJob(InventoryOrphanSweepService sweep, JobSupport jobs) {
        this.sweep = sweep;
        this.jobs = jobs;
    }

    /** 每天 03:50 —— 排在 {@code inv-recon}（03:40）之后，别和它抢库。 */
    @Scheduled(cron = "${shop.job.inv-orphan-sweep.cron:0 50 3 * * *}")
    @SchedulerLock(name = "inv-orphan-sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void scan() {
        jobs.run("inv-orphan-sweep", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "inv-orphan-sweep";
    }

    @Bean
    public JobDeclaration invOrphanSweepDeclaration() {
        return JobDeclaration.daily("inv-orphan-sweep", "空壳物料归档",
                "把来源商品已经删掉、且库存为 0 的物料归档，让它们从进货/报损的挑货列表里消失。"
                        + "还有库存的一律不动——那是要人去盘掉或报损掉的坏账，藏起来账就永远平不了",
                "shop-app", "0 50 3 * * *");
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        InventoryOrphanSweepService.Report r = sweep.sweep(false, MAX_SCAN, null);
        if (!r.complete()) {
            return JobResult.failed("未扫完：" + r.detail(), "SCAN_INCOMPLETE");
        }
        return JobResult.ok(r.detail());
    }
}
