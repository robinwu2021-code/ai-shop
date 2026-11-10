package ai.neargo.shop.beauty.job;

import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.beauty.service.impl.TaskStartCoordinator;
import ai.neargo.shop.beauty.service.impl.ServiceTaskWorkflowServiceImpl;

import java.util.Objects;

/** Only observes bounded original evidence; it cannot dispatch a saved human start. */
public final class ServiceExecutionRecoveryJob implements JobHandler {
    private final ServiceTaskExecutionService service;
    private final TaskStartCoordinator starts;
    private ServiceTaskWorkflowServiceImpl workflows;

    private ServiceExecutionRecoveryJob(
            ServiceTaskExecutionService service, TaskStartCoordinator starts) {
        this.service = Objects.requireNonNull(service);
        this.starts = starts;
    }

    /** Deployment configuration selects the legacy quantity-only capability explicitly. */
    public static ServiceExecutionRecoveryJob quantityOnly(ServiceTaskExecutionService service) {
        return new ServiceExecutionRecoveryJob(service, null);
    }

    public static ServiceExecutionRecoveryJob quantityAndStart(
            ServiceTaskExecutionService service, TaskStartCoordinator starts) {
        return new ServiceExecutionRecoveryJob(service, Objects.requireNonNull(starts));
    }

    public ServiceExecutionRecoveryJob withWorkflows(ServiceTaskWorkflowServiceImpl workflows) {
        this.workflows=Objects.requireNonNull(workflows); return this;
    }

    @Override
    public String name() {
        return "beauty-service-execution-recovery";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        try {
            int observed = service.recoverPending(100);
            if (starts != null) observed += starts.recoverPendingStarts(100);
            if (workflows != null) observed += workflows.recoverPendingWorkflows(100);
            return JobResult.ok("已核对美业执行原单：" + observed);
        } catch (RuntimeException failure) {
            return JobResult.failed("美业执行恢复暂缓", "OWNER_UNAVAILABLE");
        }
    }
}
