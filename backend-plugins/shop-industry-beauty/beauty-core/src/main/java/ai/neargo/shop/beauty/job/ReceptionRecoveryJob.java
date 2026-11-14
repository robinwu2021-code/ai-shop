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
        try { return JobResult.ok("Reception original operations examined: " + recovery.scan()); }
        catch (RuntimeException failure) {
            return JobResult.failed("Reception recovery deferred", "OWNER_UNAVAILABLE");
        }
    }
}
