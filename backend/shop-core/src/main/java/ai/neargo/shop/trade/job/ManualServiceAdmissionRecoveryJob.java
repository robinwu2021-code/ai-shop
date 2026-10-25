package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlacementService;
import org.springframework.beans.factory.annotation.Autowired;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded recovery advances only already-received originals, without reconstructing human
 * authorization.
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class ManualServiceAdmissionRecoveryJob implements JobHandler {
    private final ManualServiceAdmissionService manual;
    private final JobSupport jobs;
    @Autowired private CounterOrderPlacementService counter;

    public ManualServiceAdmissionRecoveryJob(
            ManualServiceAdmissionService manual, JobSupport jobs) {
        this.manual = manual;
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "manual-service-admission-recovery";
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "manual-service-admission-recovery", lockAtMostFor = "PT2M")
    public void scan() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        return JobResult.ok(
                "advanced="
                        + manual.recoverPending(100)
                        + ",counter="
                        + counter.recoverPending(100));
    }

    @Bean
    public JobDeclaration manualServiceAdmissionRecoveryDeclaration() {
        return new JobDeclaration(
                name(),
                "人工服务库存准入恢复",
                "仅观察或重试原库存确认，不创建收款声明",
                "shop-core",
                "0 * * * * *",
                true,
                100,
                120,
                true,
                true);
    }
}
