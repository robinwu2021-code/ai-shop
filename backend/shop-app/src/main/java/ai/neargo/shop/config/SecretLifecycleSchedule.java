package ai.neargo.shop.config;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.secret.core.SecretLifecycleJob;
import ai.neargo.secret.core.SecretLifecycleService;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 密钥轮换与到期任务的调度外壳（handler {@code secret-lifecycle}）：重叠期满退役、到期提醒与到期、主密钥轮换后的重新包裹。
 * 任务体在 secret-core 的 {@link SecretLifecycleJob}；这里只管「到点了」、加锁、记运行记录。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class SecretLifecycleSchedule implements JobHandler {

    private final SecretLifecycleJob job;
    private final JobSupport jobs;

    public SecretLifecycleSchedule(SecretLifecycleService lifecycle, JobSupport jobs) {
        this.job = new SecretLifecycleJob(lifecycle);
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.secret-lifecycle.cron:0 20 3 * * *}")
    @SchedulerLock(name = SecretLifecycleJob.NAME, lockAtMostFor = "PT15M")
    public void tick() {
        jobs.run(name(), () -> run(null).detail());
    }

    @Override
    public String name() {
        return SecretLifecycleJob.NAME;
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        return job.run(invocation);
    }

    /** 声明。每日一次：重叠期与到期都按天计，跑得更勤没有意义 */
    @Bean
    public JobDeclaration secretLifecycleDeclaration() {
        return JobDeclaration.daily(SecretLifecycleJob.NAME, "密钥轮换与到期",
                "重叠期满的旧版本退役、到期前提醒、到期停用、换主密钥后逐条重新包裹数据密钥。不跑的话旧密钥一直可用、到期无人知",
                "shop-app", "0 20 3 * * *");
    }
}
