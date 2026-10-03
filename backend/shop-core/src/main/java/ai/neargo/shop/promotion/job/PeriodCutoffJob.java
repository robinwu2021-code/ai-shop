package ai.neargo.shop.promotion.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.promotion.service.PeriodService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 社区集单的推进（TDD-营销-活动统一模型与集单 §2.3）：
 * 到点的期截单（够起订量 → 成、不够 → 等商家处理）；等处理超时的期自动取消并退款。
 *
 * <p><b>截单归属不靠这个任务</b>：订单属于哪一期在下单那一刻就按 cutoff_at 定了。
 * 任务晚跑一分钟，只影响「状态什么时候从收单中变成已成」，不影响谁在哪一期。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class PeriodCutoffJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PeriodCutoffJob.class);

    private final PeriodService periodService;
    private final JobSupport jobs;

    public PeriodCutoffJob(PeriodService periodService, JobSupport jobs) {
        this.periodService = periodService;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.period-cutoff.cron:30 * * * * *}")
    // 取消会发起退款：两个实例同时扫同一批，幂等挡得住重复退款，但会白抢一遍行锁
    @SchedulerLock(name = "period-cutoff", lockAtLeastFor = "PT30S", lockAtMostFor = "PT3M")
    public void tick() {
        jobs.run("period-cutoff", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "period-cutoff";
    }

    @Bean
    public JobDeclaration periodcutoffDeclaration() {
        return new JobDeclaration("period-cutoff", "社区集单截单与超时取消",
                "到点的集单截单；未达起订量且商家超时未处理的期自动取消并全额退款。"
                        + "不跑的话集单永远停在「收单中」，未达量的期也不会退款",
                "shop-core", "30 * * * * *", true,
                50, 180,
                true,
                false);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        long now = System.currentTimeMillis();
        int advanced = periodService.advanceDue(now);
        int refunds = periodService.cancelUndecided(now);
        if (advanced == 0 && refunds == 0) {
            return JobResult.ok(null);
        }
        log.info("[集单] 截单 {} 期，发起退款 {} 张", advanced, refunds);
        return JobResult.ok("截单 " + advanced + " 期，发起退款 " + refunds + " 张");
    }
}
