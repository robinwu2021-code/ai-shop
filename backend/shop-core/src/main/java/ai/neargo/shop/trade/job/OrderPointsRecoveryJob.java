package ai.neargo.shop.trade.job;

import ai.neargo.job.api.*;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Disabled by default; accepted points obligations can still be advanced after store detachment.
 */
@Component
@ConditionalOnProperty(
        name = {"shop.job.enabled", "shop.points.order-recovery.enabled"},
        havingValue = "true")
public class OrderPointsRecoveryJob implements JobHandler {
    private final OrderPointsRecoveryService recovery;
    private final JobSupport jobs;
    private final int limit;

    public OrderPointsRecoveryJob(
            OrderPointsRecoveryService recovery,
            JobSupport jobs,
            @Value("${shop.points.order-recovery.limit:200}") int limit) {
        this.recovery = recovery;
        this.jobs = jobs;
        this.limit = limit;
    }

    @Scheduled(cron = "${shop.points.order-recovery.cron:0 */2 * * * *}")
    @SchedulerLock(name = "order-points-recovery", lockAtMostFor = "PT10M")
    public void scan() {
        jobs.run(
                name(),
                () -> {
                    var result = run(null);
                    if (result.status() == JobStatus.FAILED)
                        throw new IllegalStateException(result.error());
                    return result.detail();
                });
    }

    @Override
    public String name() {
        return "order-points-recovery";
    }

    @Bean
    public JobDeclaration orderPointsRecoveryDeclaration() {
        return new JobDeclaration(
                name(),
                "积分订单源恢复",
                "有界重试原积分接线命令、源事件、售后与责任关闭；资金由支付域恢复",
                "shop-core",
                "0 */2 * * * *",
                true,
                480,
                600,
                true,
                true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        var result = recovery.recover(limit);
        var backlog = recovery.backlog(java.time.Instant.now());
        String detail =
                "扫描 %d 项，推进 %d 项，失败 %d 项；积压 下单 %d/%ds 售后 %d/%ds 事件 %d/%ds"
                        .formatted(
                                result.attempted(), result.completed(), result.failures().size(),
                                backlog.placements().count(), backlog.placements().oldestSeconds(),
                                backlog.refunds().count(), backlog.refunds().oldestSeconds(),
                                backlog.events().count(), backlog.events().oldestSeconds());
        if (!result.failures().isEmpty())
            return JobResult.failed(detail, result.failures().getFirst().code());
        return JobResult.ok(detail);
    }
}
