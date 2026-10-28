package ai.neargo.shop.trade.job;

import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.trade.service.ServiceExecutionService;
import ai.neargo.shop.job.JobSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Scheduler dispatch stays bounded and reports an unavailable owner as failure. */
class ServiceExecutionRecoveryJobTest {
    @Test
    void recoveryDispatchHasItsOwnNameAndNeverReplaysAPrepareCommand() {
        var service = mock(ServiceExecutionService.class);
        when(service.recoverPending(100)).thenReturn(3);
        var job = new ServiceExecutionRecoveryJob(service, mock(JobSupport.class));
        assertEquals("service-execution-recovery", job.name());
        assertNotEquals("beauty-service-execution-recovery", job.name());
        assertEquals(120, job.coreServiceExecutionRecoveryDeclaration().timeoutSec());
        assertEquals(120, job.coreServiceExecutionRecoveryDeclaration().lockAtMostSec());
        assertEquals(JobStatus.SUCCESS, job.run(null).status());
        verify(service).recoverPending(100);
        verifyNoMoreInteractions(service);
    }

    @Test
    void unavailableOriginalOwnerDoesNotProduceSuccessfulJobResult() {
        var service = mock(ServiceExecutionService.class);
        when(service.recoverPending(100)).thenThrow(new IllegalStateException("test-only unavailable owner"));
        assertEquals(JobStatus.FAILED, new ServiceExecutionRecoveryJob(service, mock(JobSupport.class)).run(null).status());
    }
}
