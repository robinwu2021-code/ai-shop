package ai.neargo.shop.beauty.job;

import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;

/** Only observes bounded original evidence; it cannot dispatch a saved human start. */
public final class ServiceExecutionRecoveryJob implements JobHandler {
    private final ServiceTaskExecutionService service;
    public ServiceExecutionRecoveryJob(ServiceTaskExecutionService service) { this.service = service; }
    @Override
    public String name() {
        return "beauty-service-execution-recovery";
    }
    @Override public JobResult run(JobInvocation invocation) {
        try { return JobResult.ok("已核对美业执行原单：" + service.recoverPending(100)); }
        catch (RuntimeException failure) {
            return JobResult.failed("美业执行恢复暂缓", "OWNER_UNAVAILABLE");
        }
    }
}
