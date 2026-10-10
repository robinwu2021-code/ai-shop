package ai.neargo.job.store;

import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code job_log} 的读写。**只有 worker 写，运营端只读。**
 *
 * <p>这张表是本库里唯一会长的那张，所以它有两道限流：
 * <ul>
 *   <li>写入侧：{@code log_every_run=0} 的任务只在**状态变化或失败**时落一行</li>
 *   <li>清理侧：{@link #purgeBefore} 由 {@code job-log-purge} 任务按天调用，保留 30 天</li>
 * </ul>
 * 两道都不设的话，一个 10 分钟级任务一年就是 5 万行，十几个任务叠起来很快就是本库最大的表。
 */
public class JobLogDao {

    private static final String COLS = """
            id, run_id, job_name, trigger_type, biz_date, started_at, finished_at,
            duration_ms, status, detail, error, worker_instance, http_status
            """;

    private final JdbcClient jdbc;

    public JobLogDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static JobLogRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobLogRow(
                rs.getLong("id"),
                rs.getString("run_id"),
                rs.getString("job_name"),
                rs.getString("trigger_type"),
                rs.getObject("biz_date", LocalDate.class),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class),
                (Long) rs.getObject("duration_ms"),
                rs.getString("status"),
                rs.getString("detail"),
                rs.getString("error"),
                rs.getString("worker_instance"),
                (Integer) rs.getObject("http_status"));
    }

    /** 发起时落一行 RUNNING。**先落库再发 HTTP** —— 否则中途进程被杀，这一轮就没有任何痕迹。 */
    public void insertStarted(String runId, String jobName, TriggerType type, LocalDate bizDate,
                              LocalDateTime startedAt, String workerInstance) {
        jdbc.sql("""
                        INSERT INTO job_log
                            (run_id, job_name, trigger_type, biz_date, started_at, status, worker_instance)
                        VALUES (:runId, :n, :type, :bizDate, :at, 'RUNNING', :inst)
                        """)
                .param("runId", runId).param("n", jobName)
                .param("type", type.name()).param("bizDate", bizDate)
                .param("at", startedAt).param("inst", workerInstance)
                .update();
    }

    /** 收结果时补完同一行。靠 run_id 认，而不是「最后一行」—— 并发时那会认错。 */
    public void finish(String runId, JobStatus status, LocalDateTime finishedAt, long durationMs,
                       String detail, String error, Integer httpStatus) {
        jdbc.sql("""
                        UPDATE job_log
                           SET status = :status, finished_at = :at, duration_ms = :ms,
                               detail = :detail, error = :error, http_status = :http
                         WHERE run_id = :runId
                        """)
                .param("status", status.name()).param("at", finishedAt).param("ms", durationMs)
                .param("detail", detail).param("error", error).param("http", httpStatus)
                .param("runId", runId)
                .update();
    }

    /** 某个任务的日志，倒序分页。运营排查时的主视图。 */
    public List<JobLogRow> findByJob(String jobName, int limit, int offset) {
        return jdbc.sql("SELECT " + COLS + " FROM job_log WHERE job_name = :n"
                        + " ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("n", jobName).param("limit", limit).param("offset", offset)
                .query(JobLogDao::map).list();
    }

    /**
     * 上一轮的状态。{@code log_every_run=0} 的任务靠它判断「状态有没有变化」——
     * 没变化就不落这一行。
     */
    public String lastStatusOf(String jobName) {
        return jdbc.sql("SELECT status FROM job_log WHERE job_name = :n"
                        + " ORDER BY started_at DESC, id DESC LIMIT 1")
                .param("n", jobName)
                .query(String.class).optional().orElse(null);
    }

    /**
     * 清理。走 {@code idx_job_log_started} 索引，否则清理任务自己会变成本库最慢的 SQL。
     *
     * <p>分批删而不是一条 DELETE 删干净：一次删几十万行会长时间持锁，
     * 而这张表同时正被 worker 写入。
     */
    public int purgeBefore(LocalDateTime before, int batchSize) {
        return jdbc.sql("DELETE FROM job_log WHERE started_at < :before LIMIT :batch")
                .param("before", before).param("batch", batchSize)
                .update();
    }
}
