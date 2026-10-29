package ai.neargo.shop.invbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ReconcileResult;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Supplier;

/** Both dispatch paths must acquire the same distributed lock before one bounded scan. */
class ExecutionStockRetentionRecoveryJobTest {
    @Test
    void contentionDoesNotInvokeContinuation() throws Throwable {
        var coordinator = mock(ExecutionStockRetentionCoordinator.class);
        var locks = mock(LockingTaskExecutor.class);
        when(locks.executeWithLock(any(LockingTaskExecutor.TaskWithResult.class), any()))
                .thenReturn(mock(LockingTaskExecutor.TaskResult.class));
        var job =
                new ExecutionStockRetentionRecoveryJob(coordinator, locks, mock(JobSupport.class));
        assertEquals(JobStatus.SKIPPED, job.run(null).status());
        verifyNoInteractions(coordinator);
    }

    @Test
    void batchHasNoCursorOrLoop() {
        var coordinator = mock(ExecutionStockRetentionCoordinator.class);
        when(coordinator.reconcileAcceptedRoutes(null, 100))
                .thenReturn(new ReconcileResult(List.of(), null));
        var job =
                new ExecutionStockRetentionRecoveryJob(
                        coordinator, mock(LockingTaskExecutor.class), mock(JobSupport.class));
        job.runBatch();
        verify(coordinator, times(1)).reconcileAcceptedRoutes(null, 100);
        verifyNoMoreInteractions(coordinator);
    }

    @SuppressWarnings("unchecked")
    @Test
    void scheduledAndManualEntryAcquireTheSameDistributedLock() throws Throwable {
        var coordinator = mock(ExecutionStockRetentionCoordinator.class);
        var locks = mock(LockingTaskExecutor.class);
        var jobs = mock(JobSupport.class);
        when(locks.executeWithLock(any(LockingTaskExecutor.TaskWithResult.class), any()))
                .thenReturn(mock(LockingTaskExecutor.TaskResult.class));
        doAnswer(
                        call -> {
                            ((Supplier<String>) call.getArgument(1)).get();
                            return null;
                        })
                .when(jobs)
                .run(eq("execution-stock-retention-recovery"), any());
        var job = new ExecutionStockRetentionRecoveryJob(coordinator, locks, jobs);
        job.scan();
        job.run(null);
        var configuration = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(locks, times(2))
                .executeWithLock(
                        any(LockingTaskExecutor.TaskWithResult.class), configuration.capture());
        for (var lock : configuration.getAllValues()) assertEquals(job.name(), lock.getName());
        verifyNoInteractions(coordinator);
    }
}
