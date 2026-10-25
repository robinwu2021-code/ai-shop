package ai.neargo.shop.trade.job;

import ai.neargo.job.api.*;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Disabled by default; accepted points obligations can still be advanced after store detachment.
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class OrderPointsRecoveryJob implements JobHandler {
    private final OrderPointsRecoveryService recovery;
    private final int limit;
    @Value("${shop.points.order-recovery.enabled:false}")
    private boolean initiallyEnabled;
    @Value("${shop.points.order-recovery.cron:0 */2 * * * *}")
    private String cron = "0 */2 * * * *";

    public OrderPointsRecoveryJob(
            OrderPointsRecoveryService recovery,
            @Value("${shop.points.order-recovery.limit:200}") int limit) {
        this.recovery = recovery;
        this.limit = limit;
    }

    // Scheduling and the shared lease are owned by JobRunner / JobHandlerEndpoint.
    // No local @Scheduled path may bypass the operator-controlled task definition.
    @Override
    public String name() {
        return "order-points-recovery";
    }

    @Bean
    public JobDeclaration orderPointsRecoveryDeclaration() {
        return new JobDeclaration("order-points-recovery",
                "积分订单源恢复",
                "有界重试原积分接线命令、源事件、售后与责任关闭；资金由支付域恢复",
                "shop-core",
                cron,
                initiallyEnabled,
                480,
                600,
                true,
                true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int batch;
        try {
            batch = invocation == null ? limit : Integer.parseInt(invocation.param("limit", Integer.toString(limit)));
        } catch (NumberFormatException invalid) {
            return JobResult.failed("每批条数必须为 1–200", "POINTS_BATCH_INVALID");
        }
        if (batch < 1 || batch > 200) return JobResult.failed("每批条数必须为 1–200", "POINTS_BATCH_INVALID");
        var result = recovery.recover(batch);
        var backlog = recovery.backlog(java.time.Instant.now());
        String detail =
                "扫描 %d 项，推进 %d 项，失败 %d 项；积压 下单 %d/%ds 售后 %d/%ds 人工售后 %d/%ds 事件 %d/%ds"
                        .formatted(
                                result.attempted(), result.completed(), result.failures().size(),
                                backlog.placements().count(), backlog.placements().oldestSeconds(),
                                backlog.refunds().count(), backlog.refunds().oldestSeconds(),
                                backlog.manualRefunds().count(), backlog.manualRefunds().oldestSeconds(),
                                backlog.events().count(), backlog.events().oldestSeconds());
        if (!result.failures().isEmpty())
            return JobResult.failed(detail, result.failures().getFirst().code());
        return JobResult.ok(detail);
    }
}
