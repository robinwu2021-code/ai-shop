package ai.neargo.shop.reportbridge;

import java.util.function.Supplier;

import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.report.config.ReportStorageInitializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportStorageInitJobTest {

    @Test
    void failedInitializationRemainsFailedForWorkerAndLocalScheduler() {
        ReportStorageInitializer initializer = mock(ReportStorageInitializer.class);
        JobSupport jobs = mock(JobSupport.class);
        when(initializer.state()).thenReturn(ReportStorageInitializer.State.FAILED);
        when(initializer.failureCode()).thenReturn("FlywayException");
        doThrow(new IllegalStateException("private connection detail"))
                .when(initializer).initialize();
        ReportStorageInitJob job = new ReportStorageInitJob(initializer, jobs);
        var result = job.run(null);
        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.error()).isEqualTo("FlywayException");
        assertThat(result.detail()).doesNotContain("private");
        doAnswer(call -> {
            Supplier<?> action = call.getArgument(1);
            assertThatThrownBy(action::get).isInstanceOf(IllegalStateException.class)
                    .hasMessage("REPORT_STORAGE_NOT_READY:FlywayException");
            return null;
        }).when(jobs).run(eq("report-storage-init"), any());
        job.initializeStorage();
    }

    @Test
    void readyStorageDoesNotCreatePeriodicSuccessNoise() {
        ReportStorageInitializer initializer = mock(ReportStorageInitializer.class);
        JobSupport jobs = mock(JobSupport.class);
        when(initializer.state()).thenReturn(ReportStorageInitializer.State.READY);
        new ReportStorageInitJob(initializer, jobs).initializeStorage();
        verifyNoInteractions(jobs);
    }
}
