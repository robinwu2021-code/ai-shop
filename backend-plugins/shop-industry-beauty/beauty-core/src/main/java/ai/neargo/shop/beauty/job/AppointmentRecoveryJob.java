package ai.neargo.shop.beauty.job;

import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.beauty.service.impl.AppointmentRecovery;

/** Registered with the existing job framework; deployment can schedule bounded reconciliation without user
 * impersonation.
 */
public final class AppointmentRecoveryJob implements JobHandler {
    private final AppointmentRecovery recovery;
    public AppointmentRecoveryJob(AppointmentRecovery recovery) { this.recovery = recovery; }
    @Override public String name() { return "beauty-appointment-recovery"; }
    @Override public JobResult run(JobInvocation invocation) {
        try {
            var report = recovery.scanReport();
            String detail = "Appointment recovery: " + report.detail();
            if (report.failed() > 0) { return JobResult.failed(detail, "APPOINTMENT_RECOVERY_FAILED"); }
            if (report.pending() > 0) { return JobResult.failed(detail, "APPOINTMENT_RECOVERY_PENDING"); }
            return JobResult.ok(detail);
        } catch (RuntimeException failure) {
            return JobResult.failed("Appointment recovery scan failed", failure.getClass().getSimpleName());
        }
    }
}
