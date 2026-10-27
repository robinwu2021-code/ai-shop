package ai.neargo.secret.core;

import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;

/**
 * 轮换与到期任务（handler {@code secret-lifecycle}）。只是 {@link SecretLifecycleService#runOnce()} 的任务外壳；
 * 计时、加锁、运行记录由宿主的调度外壳负责（shop-app {@code SecretLifecycleSchedule}）。
 */
public class SecretLifecycleJob implements JobHandler {

    public static final String NAME = "secret-lifecycle";

    private final SecretLifecycleService lifecycle;

    public SecretLifecycleJob(SecretLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        SecretLifecycleService.Summary s = lifecycle.runOnce();
        return s.failed() > 0 ? JobResult.failed(s.detail(), "SECRET_LIFECYCLE_PENDING") : JobResult.ok(s.detail());
    }
}
