package ai.neargo.shop.food.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.food.service.DiningTransactionRecoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Discovered by the platform's existing job runtime; no scheduler or persistence runtime is created in FOOD web. */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class DiningTransactionRecoveryJob implements JobHandler {
    private static final String NAME = "food-transaction-recovery";
    private final DiningTransactionRecoveryService recovery;
    public DiningTransactionRecoveryJob(DiningTransactionRecoveryService recovery) { this.recovery = recovery; }
    @Override public String name() { return NAME; }
    @Override public JobResult run(JobInvocation invocation) {
        try {
            var result = recovery.recoverDue(50);
            String detail = "Source projections: claimed=" + result.claimed() + ", synchronized=" + result.complete()
                    + ", pending=" + result.pending() + ", failed=" + result.failed();
            return result.failed() == 0 ? JobResult.ok(detail) : JobResult.failed(detail, "FOOD_SOURCE_RECOVERY_PENDING");
        } catch (RuntimeException unavailable) {
            return JobResult.failed("FOOD source recovery unavailable", unavailable.getClass().getSimpleName());
        }
    }
    @Bean public JobDeclaration diningTransactionRecoveryDeclaration() {
        return new JobDeclaration(NAME, "餐饮原交易进度恢复", "仅查询并同步原订单、收款及售后，不创建新的交易或扣款",
                "food-web", "0 */2 * * * *", true, 600, 660, true, false);
    }
}
