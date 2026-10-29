package ai.neargo.shop.invbridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Sole HOST scheduling owner continues only durable accepted routes; no reference or first HUMAN
 * acceptance.
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public final class ExecutionStockRetentionRecoveryJob implements JobHandler {
    private static final String NAME = "execution-stock-retention-recovery";
    private static final Duration LOCK = Duration.ofHours(2);
    private final ExecutionStockRetentionCoordinator coordinator;
    private final LockingTaskExecutor locks;
    private final JobSupport jobs;

    public ExecutionStockRetentionRecoveryJob(
            ExecutionStockRetentionCoordinator coordinator,
            LockingTaskExecutor locks,
            JobSupport jobs) {
        this.coordinator = coordinator;
        this.locks = locks;
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Scheduled(cron = "${shop.job.execution-stock-retention-recovery.cron:0 * * * * *}")
    public void scan() {
        jobs.run(NAME, () -> runBatchLocked().detail());
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        return runBatchLocked();
    }

    /**
     * HTTP dispatch, in-process dispatch and scheduled invocation share this exact distributed
     * lock.
     */
    public JobResult runBatchLocked() {
        try {
            var result =
                    locks.executeWithLock(
                            (LockingTaskExecutor.TaskWithResult<JobResult>) this::runBatch,
                            new LockConfiguration(Instant.now(), NAME, LOCK, Duration.ZERO));
            return result.wasExecuted() ? result.getResult() : JobResult.skipped();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("accepted retention recovery unavailable", failure);
        }
    }

    /**
     * One bounded batch; retry metadata belongs to the existing owner rows, never a second queue or
     * cursor.
     */
    JobResult runBatch() {
        var result = coordinator.reconcileAcceptedRoutes(null, 100);
        long pending =
                result.receipts().stream().filter(r -> r.outcome() != Outcome.RETAINED).count();
        String detail =
                "观察%d，待处理%d，失败%d"
                        .formatted(result.receipts().size(), pending, result.failures().size());
        return result.failures().isEmpty() && pending == 0
                ? JobResult.ok(detail)
                : JobResult.failed(detail, "ACCEPTED_RETENTION_PENDING");
    }

    /**
     * Declaration registers API dispatch without enabling timers outside existing worker
     * scheduling.
     */
    @Bean
    public JobDeclaration executionStockRetentionRecoveryDeclaration() {
        return new JobDeclaration(
                NAME,
                "执行库存原意图恢复",
                "仅续办HOST已接受原件，不开工或释放库存",
                "shop-app",
                "0 * * * * *",
                true,
                3600,
                7200,
                true,
                true);
    }
}
