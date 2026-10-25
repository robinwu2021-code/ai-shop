package ai.neargo.shop.reportbridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.report.config.ReportStorageInitializer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** J13：报表库故障只影响本任务；不自动repair，不把连接错误内容回传运营端。 */
@Component
@ConditionalOnProperty(name = {"shop.report.enabled", "shop.job.enabled"}, havingValue = "true")
public class ReportStorageInitJob implements JobHandler {

    private static final String NAME = "report-storage-init";
    private final ReportStorageInitializer initializer;
    private final JobSupport jobs;

    public ReportStorageInitJob(ReportStorageInitializer initializer, JobSupport jobs) {
        this.initializer = initializer;
        this.jobs = jobs;
    }

    @Override
    public String name() { return NAME; }

    @Scheduled(cron = "${shop.job.report-storage-init.cron:0 * * * * *}")
    @SchedulerLock(name = NAME, lockAtMostFor = "PT5M")
    public void initializeStorage() {
        if (initializer.state() != ReportStorageInitializer.State.READY) {
            jobs.run(NAME, () -> {
                JobResult result = run(null);
                if (result.status() == JobStatus.FAILED) {
                    throw new IllegalStateException("REPORT_STORAGE_NOT_READY:" + result.error());
                }
                return result.detail();
            });
        }
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        try {
            initializer.initialize();
            return JobResult.ok("REPORT_STORAGE_READY");
        } catch (RuntimeException ex) {
            return JobResult.failed("REPORT_STORAGE_NOT_READY", initializer.failureCode());
        }
    }

    @Bean
    public JobDeclaration reportStorageInitDeclaration() {
        return new JobDeclaration(NAME, "报表库初始化与恢复",
                "校验独立统计库；仅指定迁移角色执行迁移，失败不影响点餐业务，不自动repair。",
                "shop-app", "0 * * * * *", true, 240, 300, true, false);
    }
}
