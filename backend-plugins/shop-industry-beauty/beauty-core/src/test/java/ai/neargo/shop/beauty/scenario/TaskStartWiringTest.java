package ai.neargo.shop.beauty.scenario;

import static org.mockito.ArgumentMatchers.any;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.beauty.job.ServiceExecutionRecoveryJob;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.beauty.service.impl.TaskStartCoordinator;
import ai.neargo.shop.beauty.service.impl.TaskStartOriginalReader;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * SOURCE ONLY / NOT_RUN. The same leaf job has explicit deployment capabilities, never a second
 * scanner.
 */
class TaskStartWiringTest {
    @Test
    void readerConstructorHasNoOwnerOrOrchestrationBackEdge() {
        var names =
                Arrays.stream(
                                TaskStartOriginalReader.class.getConstructors()[0]
                                        .getParameterTypes())
                        .map(Class::getName)
                        .toList();
        assertFalse(
                names.stream()
                        .anyMatch(
                                n ->
                                        n.endsWith("Port")
                                                || n.endsWith("TaskStartCoordinator")
                                                || n.endsWith("ServiceTaskExecutionServiceImpl")
                                                || n.endsWith(
                                                        "ServiceTaskStockPreparationCoordinator")
                                                || n.endsWith("ServiceTaskActionServiceImpl")
                                                || n.endsWith("TaskAcceptanceCoordinator")));
    }

    @Test
    void oneLeafPreservesQuantityOnlyAndAddsBoundedStartRecoveryWhenConfigured() {
        var quantity = mock(ServiceTaskExecutionService.class);
        var starts = mock(TaskStartCoordinator.class);
        var legacy = ServiceExecutionRecoveryJob.quantityOnly(quantity);
        assertNotNull(legacy.run(null));
        verify(quantity).recoverPending(100);
        verifyNoInteractions(starts);
        clearInvocations(quantity);
        var embedded = ServiceExecutionRecoveryJob.quantityAndStart(quantity, starts);
        assertEquals(legacy.name(), embedded.name());
        assertNotNull(embedded.run(null));
        verify(quantity).recoverPending(100);
        verify(starts).recoverPendingStarts(100);
        verify(starts, never()).start(any(), any());
    }
}
