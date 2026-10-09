package ai.neargo.shop.logisticsbridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.logistics.compensation.CompensationSweeper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 物流补偿作业 {@code logistics-compensate}（TDD-物流模块 M8，AC8）—— 物流最终唯一的定时作业。
 *
 * <p>只处理明确的场景（订阅停在 PENDING、推送沉默），正常推送中的运单一单都不碰。
 * 物流模块不依赖 job-api，作业壳放装配层（同进销存的 InventoryJobHandlers）。
 * ⚠️ 新作业注册后在作业表里<b>默认是关的</b>，上线后要去作业表打开。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class LogisticsCompensationJob implements JobHandler {

    static final String NAME = "logistics-compensate";

    private final CompensationSweeper sweeper;
    private final JobSupport jobs;
    private final int limit;

    public LogisticsCompensationJob(CompensationSweeper sweeper, JobSupport jobs,
                                    @Value("${shop.job.logistics-compensate.limit:200}") int limit) {
        this.sweeper = sweeper;
        this.jobs = jobs;
        this.limit = limit;
    }

    // 每小时 15 分：错开整点那一批交易域作业
    @Scheduled(cron = "${shop.job.logistics-compensate.cron:0 15 * * * *}")
    @SchedulerLock(name = NAME, lockAtLeastFor = "PT1M", lockAtMostFor = "PT9M")
    public void poll() {
        jobs.run(NAME, () -> run(null).detail());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Bean
    public JobDeclaration logisticsCompensateDeclaration() {
        return new JobDeclaration(NAME, "物流补偿",
                "订阅停在待订阅超过 10 分钟的补订（订阅总开关刚打开 / 事件重试耗尽）；"
                        + "批 3 起加「订阅成功但 24 小时没有任何推送」的在途单去问微信。正常推送中的运单不碰",
                "shop-app", "0 15 * * * *", true,
                540, 600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        return JobResult.ok(sweeper.sweep(limit).detail());
    }
}
