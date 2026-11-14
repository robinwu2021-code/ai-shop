package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.channel.followup.ChannelFollowUpRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 「未知」补查的调度壳 —— 判断都在 {@link ChannelFollowUpRunner}（TDD-支付服务-I2渠道统一封装 S5）。
 * 每分钟一轮；任务自带退避（30 秒起、封顶 1 小时、12 次转人工），这里只决定多久看一次到期的。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class ChannelFollowUpJob implements JobHandler {

    @Value("${shop.job.channel-follow-up.limit:200}")
    private int limit;

    private final ChannelFollowUpRunner followUps;
    private final JobSupport jobs;

    public ChannelFollowUpJob(ChannelFollowUpRunner followUps, JobSupport jobs) {
        this.followUps = followUps;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.channel-follow-up.cron:15 * * * * *}")
    @SchedulerLock(name = "channel-follow-up", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void scan() {
        jobs.run("channel-follow-up", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "channel-follow-up";
    }

    @Bean
    public JobDeclaration channelFollowUpDeclaration() {
        return new JobDeclaration("channel-follow-up", "渠道结果不明补查",
                "渠道调用超时、5xx、未登记渠道码时登记的补查：按原追踪号查单，查到已付走与回调同一条入账路径（幂等），"
                        + "没有这笔就结案，仍无结论按退避再查，12 次转人工",
                "shop-app", "15 * * * * *", true,
                240, 300, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        return JobResult.ok("处理 %d 条到期补查".formatted(followUps.runOnce(limit)));
    }
}
