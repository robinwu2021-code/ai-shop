package ai.neargo.shop.paybridge;

import static ai.neargo.shop.common.config.ModuleDataSources.MEMBER_MARKETING_BINDING;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A target worker can take over even while the pay deployment holds the general recovery job lease. */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@ConditionalOnProperty(name = MEMBER_MARKETING_BINDING)
public class TargetRefundRecoveryJob implements JobHandler {
    private final EntitlementRecoveryService recovery;
    private final JobSupport jobs;
    private long cursor;

    public TargetRefundRecoveryJob(EntitlementRecoveryService recovery, JobSupport jobs) {
        this.recovery = recovery;
        this.jobs = jobs;
    }

    @Override public String name() { return "entitlement-target-refund"; }

    @Bean public JobDeclaration targetRefundRecoveryDeclaration() {
        return new JobDeclaration(name(), "目标库储值退款恢复", "按交易库租户归属恢复原退款决定",
                "shop-app", "30 */2 * * * *", true, 90, 120, true, true);
    }

    @Scheduled(cron = "${shop.job.entitlement-recovery.cron:30 */2 * * * *}")
    @SchedulerLock(name = "entitlement-target-refund", lockAtMostFor = "PT90S")
    public void tick() { jobs.run(name(), () -> run(null).detail()); }

    @Override public synchronized JobResult run(JobInvocation invocation) {
        var result = recovery.recover("MAIN", Kind.SERVICE_REFUND, cursor, 100);
        cursor = result.nextId();
        String detail = "扫描%d，完成%d，待查%d，失败%d".formatted(
                result.scanned(), result.completed(), result.deferred(), result.failed());
        return result.failed() > 0 ? JobResult.failed(detail, "ENTITLEMENT_RECOVERY_PENDING") : JobResult.ok(detail);
    }
}
