package ai.neargo.shop.message.notify;

import org.springframework.context.annotation.Bean;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 营销广播下发（设计：触达推送中台-模块抽象 · N6）。到点把 QUEUED 的推送任务发出去。
 *
 * <p><b>频率 1 分钟</b>：营销广播不是「钱到账了」那种秒级的事，定时下发本就以分钟计；
 * 没有到点任务时这一轮就是一次带索引的查询。
 *
 * <p><b>@Profile("worker") + @SchedulerLock</b>：与所有定时任务同规矩 ——
 * 只在 worker 实例跑、且两个实例不会把同一批任务各发一遍（营销重复推送很扰民）。
 */
@Profile("worker")
@Component
public class NotifyPushTaskJob implements JobHandler {

    private final NotifyPushTaskService service;
    private final JobSupport jobs;

    public NotifyPushTaskJob(NotifyPushTaskService service, JobSupport jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.push-task.cron:0 * * * * *}")
    @SchedulerLock(name = "notify-push-task", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatch() {
        // 触发器只负责「到点了」；任务体在 run() 里。J1 只搬不改
        jobs.run("notify-push-task", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "notify-push-task";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration notifypushtaskDeclaration() {
        return new JobDeclaration("notify-push-task", "定时推送下发",
                "把到点的广播推送任务下发给通道。不跑的话运营配的定时推送永远不发出去",
                "shop-core", "0 * * * * *", true, 60, 600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int tasks = service.dispatchDue();
        // 没有到点任务是常态；**detail 保持 null**，JobSupport 用它区分「跑了但没事」
        return JobResult.ok(tasks == 0 ? null : "下发 " + tasks + " 个广播任务");
    }
}
