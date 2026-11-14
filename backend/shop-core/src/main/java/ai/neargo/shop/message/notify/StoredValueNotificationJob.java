package ai.neargo.shop.message.notify;

import org.springframework.context.annotation.Bean;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker uses the existing job switch, declaration, lock and audit facilities. */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class StoredValueNotificationJob implements JobHandler {

    private final StoredValueNotificationService service;
    private final JobSupport jobs;

    public StoredValueNotificationJob(StoredValueNotificationService service, JobSupport jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.stored-value-notification.cron:0 * * * * *}")
    @SchedulerLock(name = "stored-value-notification", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatch() {
        // 触发器只负责「到点了」；任务体在 run() 里。J1 只搬不改
        jobs.run("stored-value-notification", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "stored-value-notification";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration storedValueNotificationDeclaration() {
        return new JobDeclaration("stored-value-notification", "储值消费通知下发",
                "发送已提交的储值消费通知，未知结果不自动重试",
                "shop-core", "0 * * * * *", true,
                50, 180,
                true,
                // 同 order-auto-close：每分钟的任务用稀疏日志
                false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int tasks = service.dispatchDue();
        // 没有到点任务是常态；**detail 保持 null**，JobSupport 用它区分「跑了但没事」
        return JobResult.ok(tasks == 0 ? null : "下发 " + tasks + " 个储值通知任务");
    }
}
