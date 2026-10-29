package ai.neargo.inventory.domain.job;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventorySnapshotService;
import ai.neargo.inventory.domain.spi.InventoryJobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 日快照跑批。T+1 结算昨天。
 *
 * <p><b>只结算昨天，不补历史</b>：补历史是一次性的活，该由人挑时间跑
 * （重放一年流水要一个专门的窗口），不该让日常任务顺手做 ——
 * 顺手做的表现是某天凌晨这个任务跑了四个小时，而没人知道为什么。
 */
@ConditionalOnProperty(name = "inventory.jobs.enabled", havingValue = "true")
@ConditionalOnInventory
@Component
public class InvDailySnapshotJob {

    private final InventorySnapshotService snapshots;
    private final InventoryJobRunner jobs;

    public InvDailySnapshotJob(InventorySnapshotService snapshots, InventoryJobRunner jobs) {
        this.snapshots = snapshots;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${inventory.jobs.snapshot-cron:0 30 3 * * *}")
    @SchedulerLock(name = "inv-daily-snapshot", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void run() {
        jobs.run("inv-daily-snapshot", this::buildYesterday);
    }

    /** 建昨天的快照。**两条触发路径共用它**，见 {@code InvOutboxDispatchJob.dispatchOnce}。 */
    public String buildYesterday() {
        LocalDate day = LocalDate.now().minusDays(1);
        return "date=" + day + " rows=" + snapshots.buildFor(day);
    }
}
