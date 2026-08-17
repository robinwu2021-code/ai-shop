package ai.neargo.shop.message.notify;

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
public class NotifyPushTaskJob {

    private final NotifyPushTaskService service;
    private final JobSupport jobs;

    public NotifyPushTaskJob(NotifyPushTaskService service, JobSupport jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.push-task.cron:0 * * * * *}")
    @SchedulerLock(name = "notify-push-task", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatch() {
        jobs.run("notify-push-task", () -> {
            int tasks = service.dispatchDue();
            return tasks == 0 ? null : "下发 " + tasks + " 个广播任务";   // 没有到点任务是常态
        });
    }
}
