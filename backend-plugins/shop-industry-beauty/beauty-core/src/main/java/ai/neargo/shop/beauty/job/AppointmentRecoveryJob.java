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
        try { return JobResult.ok("Appointment original operations examined: " + recovery.scan()); }
        catch (RuntimeException failure) { return JobResult.failed("Appointment recovery deferred",
                "OWNER_UNAVAILABLE"); }
    }
}
