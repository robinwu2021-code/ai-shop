package ai.neargo.shop.config;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.event.OutboxDispatchJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把**平台自己的** outbox 投递接到独立调度器上。
 *
 * <h2>它此前两条路都不通</h2>
 *
 * {@link OutboxDispatchJob} 挂着 {@code @Scheduled}，但：
 * <ul>
 *   <li>{@code @EnableScheduling} 在 {@code SchedulingConfig} 上挂着 {@code @Profile("worker")}，
 *       而生产的 profile 是 {@code api,ops} —— 注解<b>连解析都不会发生</b></li>
 *   <li>独立调度器那条路要靠 {@link JobDeclaration} 进 {@code job_definition}，
 *       而这个任务<b>没有声明</b>（2026-09-02 由 {@code ScheduledJobConventionTest} 的新守卫逮到）</li>
 * </ul>
 *
 * <h2>为什么今天看不出问题</h2>
 *
 * 线上 {@code sys_outbox} <b>0 行</b> —— 没有订单，就没有事件。所以「投递从来没跑过」
 * 与「没有东西要投」在现象上完全一样。**第一条事件写进去的那一刻，它就会永远躺在那儿**，
 * 而站内信、履约通知这些都走这条链。
 *
 * <p>进销存那三个任务是同一个形状（见 {@code InventoryJobHandlers}），
 * 且**它们的事件正是投到这张表里** —— 只修上游不修这里，等于把积压从
 * {@code inv_outbox} 挪到 {@code sys_outbox}，一条也没真送出去。
 *
 * <h2>为什么声明写在这里而不是任务旁边</h2>
 *
 * {@code OutboxDispatchJob} 住在 {@code shop-store-mybatis}，那个模块<b>不依赖
 * {@code shop-job-api}</b>。平台侧其余的声明也都在 {@code shop-app} 下，本类照办。
 */
@Configuration
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@ConditionalOnBean(OutboxDispatchJob.class)
public class PlatformOutboxJobHandler {

    @Bean
    public JobHandler outboxDispatchHandler(OutboxDispatchJob job) {
        return new JobHandler() {
            @Override
            public String name() {
                return "outbox-dispatch";
            }

            @Override
            public JobResult run(JobInvocation invocation) {
                try {
                    String detail = job.dispatchOnce();
                    // dispatchOnce 用 null 表示「这轮没事可做」，那是常态不是失败
                    return JobResult.ok(detail == null ? "pending=0" : detail);
                } catch (RuntimeException e) {
                    // 不抛出去：抛了会变成 HTTP 5xx，调度器只能记成「调不通」，
                    // 而那与「跑了但失败了」在排查时是两件事
                    return JobResult.failed(
                            e.getClass().getSimpleName() + ": " + e.getMessage(), "UNEXPECTED");
                }
            }
        };
    }

    /**
     * <b>5 秒一轮</b>，与 {@code OutboxDispatchJob} 上 {@code @Scheduled} 的默认 cron 一致 ——
     * 两处写不同的值，将来看到的人不知道哪个在生效。
     *
     * <p>{@code logEveryRun=false}：5 秒一轮，全记的话运行记录一天两万条，
     * 而其中绝大多数是「没事可做」。
     */
    @Bean
    public JobDeclaration outboxDispatchDeclaration() {
        return new JobDeclaration("outbox-dispatch", "平台事件投递",
                "把 sys_outbox 里的领域事件投给消费者。站内信、履约通知都走这条链 —— "
                        + "它不跑的时候没有任何报错，只是消息再也发不出去",
                "shop-app", "*/5 * * * * *", true, 60, 120, true, false);
    }
}
