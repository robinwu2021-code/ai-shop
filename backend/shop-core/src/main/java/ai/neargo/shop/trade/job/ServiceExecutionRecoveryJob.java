package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.trade.service.ServiceExecutionService;

/** Only observes bounded original evidence; it cannot dispatch a saved human start. */
@Component("coreServiceExecutionRecoveryJob")
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class ServiceExecutionRecoveryJob implements JobHandler {
    private final ServiceExecutionService service;
    private final JobSupport jobs;
    public ServiceExecutionRecoveryJob(ServiceExecutionService service, JobSupport jobs) {
        this.service = service;
        this.jobs = jobs;
    }
    /** HOST worker SchedulingConfig activates this timer; the API process only registers the handler. */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "service-execution-recovery", lockAtMostFor = "PT2M")
    public void scan() {
        jobs.run(name(), () -> "已核对服务执行原单：" + service.recoverPending(100));
    }

    @Bean(name = "coreServiceExecutionRecoveryDeclaration")
    public JobDeclaration coreServiceExecutionRecoveryDeclaration() {
        return new JobDeclaration(name(), "服务数量授权恢复",
                "仅核对既有执行来源，不自动开工或释放数量", "shop-core",
                "0 * * * * *", true, 120, 120, true, true);
    }
    @Override
    public String name() {
        return "service-execution-recovery";
    }
    @Override public JobResult run(JobInvocation invocation) {
        try { return JobResult.ok("已核对服务执行原单：" + service.recoverPending(100)); }
        catch (RuntimeException failure) {
            return JobResult.failed("服务执行恢复暂缓", "OWNER_UNAVAILABLE");
        }
    }
}
