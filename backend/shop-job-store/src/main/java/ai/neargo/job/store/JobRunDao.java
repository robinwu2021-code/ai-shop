package ai.neargo.job.store;

import ai.neargo.job.api.JobStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code job_run} 的读写。**只有 worker 写，运营端只读。**
 *
 * <p>这张表回答的是运营最常问的那个问题：「它上次什么时候跑的、跑成了没有」。
 * worker 挂了的时候它照样答得出来（「最后一次是 2 小时前」）——
 * 这正是页面直读库、不向 worker 要数据的理由。
 */
public class JobRunDao {

    private static final String COLS = """
            id, job_name, last_run_at, last_status, duration_ms, detail, error,
            consecutive_failures, run_count, next_run_at, running, current_run_id, updated_at
            """;

    private final JdbcClient jdbc;

    public JobRunDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static JobRunRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobRunRow(
                rs.getLong("id"),
                rs.getString("job_name"),
                rs.getObject("last_run_at", LocalDateTime.class),
                rs.getString("last_status"),
                (Long) rs.getObject("duration_ms"),
                rs.getString("detail"),
                rs.getString("error"),
                rs.getInt("consecutive_failures"),
                rs.getLong("run_count"),
                rs.getObject("next_run_at", LocalDateTime.class),
                rs.getBoolean("running"),
                rs.getString("current_run_id"),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    public List<JobRunRow> findAll() {
        return jdbc.sql("SELECT " + COLS + " FROM job_run ORDER BY job_name")
                .query(JobRunDao::map).list();
    }

    public JobRunRow findByName(String jobName) {
        return jdbc.sql("SELECT " + COLS + " FROM job_run WHERE job_name = :n")
                .param("n", jobName)
                .query(JobRunDao::map).optional().orElse(null);
    }

    /** 发起一轮：标记在跑。**这一步先落库再发 HTTP** —— 否则业务侧跑起来了而表上还是空的。 */
    public void markStarted(String jobName, String runId, LocalDateTime startedAt) {
        int updated = jdbc.sql("""
                        UPDATE job_run
                           SET running = 1, current_run_id = :runId, last_run_at = :at
                         WHERE job_name = :n
                        """)
                .param("runId", runId).param("at", startedAt).param("n", jobName)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO job_run (job_name, running, current_run_id, last_run_at)
                            VALUES (:n, 1, :runId, :at)
                            """)
                    .param("n", jobName).param("runId", runId).param("at", startedAt)
                    .update();
        }
    }

    /**
     * 收一轮的结果。
     *
     * <p><b>{@code consecutive_failures} 只在 FAILED 时 +1</b>，SUCCESS 清零，
     * 其余状态（SKIPPED / TIMEOUT / UNREACHABLE）**原样不动**：
     * <ul>
     *   <li>SKIPPED 是正常的并发保护，不是故障</li>
     *   <li>TIMEOUT 是结果未知 —— 业务侧多半还在跑，判它失败是冤枉</li>
     *   <li>UNREACHABLE 是 worker 侧的事（业务多半正在发布），不是这个任务的问题</li>
     * </ul>
     * 把它们算进去，告警就会在一切正常时响，而那样的告警等于没有告警。
     *
     * <p>{@code run_count} 则每轮都加 —— 它统计的是「发起过几次」，与成败无关。
     */
    public void markFinished(String jobName, JobStatus status, long durationMs,
                             String detail, String error) {
        String failureExpr = switch (status) {
            case SUCCESS -> "0";
            case FAILED -> "consecutive_failures + 1";
            case SKIPPED, TIMEOUT, UNREACHABLE, RUNNING -> "consecutive_failures";
        };
        jdbc.sql("""
                        UPDATE job_run
                           SET running = 0,
                               current_run_id = NULL,
                               last_status = :status,
                               duration_ms = :ms,
                               detail = :detail,
                               error = :error,
                               run_count = run_count + 1,
                               consecutive_failures = %s
                         WHERE job_name = :n
                        """.formatted(failureExpr))
                .param("status", status.name())
                .param("ms", durationMs)
                .param("detail", detail)
                .param("error", error)
                .param("n", jobName)
                .update();
    }

    /** 下次什么时候跑。注册表算好后写进来，页面上直接显示，省得运营自己解 cron。 */
    public void updateNextRunAt(String jobName, LocalDateTime nextRunAt) {
        jdbc.sql("UPDATE job_run SET next_run_at = :at WHERE job_name = :n")
                .param("at", nextRunAt).param("n", jobName).update();
    }
}
