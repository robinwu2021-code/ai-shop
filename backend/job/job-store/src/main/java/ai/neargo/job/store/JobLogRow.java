package ai.neargo.job.store;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** {@code job_log} 一行：一轮执行。 */
public record JobLogRow(
        Long id,
        String runId,
        String jobName,
        String triggerType,
        LocalDate bizDate,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        String status,
        String detail,
        String error,
        String workerInstance,
        Integer httpStatus) {
}
