package ai.neargo.job.store;

import java.time.LocalDateTime;

/** {@code job_run} 一行：一个任务的当前状态。 */
public record JobRunRow(
        Long id,
        String jobName,
        LocalDateTime lastRunAt,
        String lastStatus,
        Long durationMs,
        String detail,
        String error,
        int consecutiveFailures,
        long runCount,
        LocalDateTime nextRunAt,
        boolean running,
        String currentRunId,
        LocalDateTime updatedAt) {
}
