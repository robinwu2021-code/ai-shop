package ai.neargo.shop.beauty.job;

import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.beauty.service.impl.ReceptionRecovery;

/** The existing worker scheduler drives bounded original-resource reconciliation without a staff session. */
public final class ReceptionRecoveryJob implements JobHandler {
    private final ReceptionRecovery recovery;
    public ReceptionRecoveryJob(ReceptionRecovery recovery) { this.recovery = recovery; }
    @Override public String name() { return "beauty-reception-recovery"; }
    @Override public JobResult run(JobInvocation invocation) {
        try {
            var report = recovery.scanReport();
            String detail = "Reception recovery: " + report.detail();
            if (report.failed() > 0) { return JobResult.failed(detail, "RECEPTION_RECOVERY_FAILED"); }
            if (report.pending() > 0) { return JobResult.failed(detail, "RECEPTION_RECOVERY_PENDING"); }
            return JobResult.ok(detail);
        } catch (RuntimeException failure) {
            return JobResult.failed("Reception recovery scan failed", failure.getClass().getSimpleName());
        }
    }
}
