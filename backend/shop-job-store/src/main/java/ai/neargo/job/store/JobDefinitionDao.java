package ai.neargo.job.store;

import ai.neargo.job.api.JobDeclaration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code job_definition} 的读写。**这张表只有运营端与启动时的 upsert 会写。**
 *
 * <p>本类最要紧的是 {@link #upsertFromCode}，它实现了一条**被违反时不会有任何声音**的规则，
 * 见那个方法的注释。
 */
public class JobDefinitionDao {

    private static final String COLS = """
            id, job_name, display_name, description, handler_name, target, params, cron,
            enabled, timeout_sec, lock_at_most_sec, manual_trigger, log_every_run,
            source, missing, owner_module, created_at, updated_at, updated_by,
            trigger_requested_at, last_triggered_at
            """;

    private final JdbcClient jdbc;

    public JobDefinitionDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 显式 RowMapper，不用反射映射。
     *
     * <p>多写这十几行换来的是：列名改了**编译期就炸**，而不是运行期悄悄给出一个 null；
     * 以及 native 镜像不需要为这个 record 配反射。
     */
    private static JobDefinitionRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobDefinitionRow(
                rs.getLong("id"),
                rs.getString("job_name"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("handler_name"),
                rs.getString("target"),
                rs.getString("params"),
                rs.getString("cron"),
                rs.getBoolean("enabled"),
                rs.getInt("timeout_sec"),
                rs.getInt("lock_at_most_sec"),
                rs.getBoolean("manual_trigger"),
                rs.getBoolean("log_every_run"),
                rs.getString("source"),
                rs.getBoolean("missing"),
                rs.getString("owner_module"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("updated_by"),
                rs.getObject("trigger_requested_at", LocalDateTime.class),
                rs.getObject("last_triggered_at", LocalDateTime.class));
    }

    public List<JobDefinitionRow> findAll() {
        return jdbc.sql("SELECT " + COLS + " FROM job_definition ORDER BY job_name")
                .query(JobDefinitionDao::map).list();
    }

    /** worker 注册用：只要开着的、且代码里还存在的。 */
    public List<JobDefinitionRow> findSchedulable() {
        return jdbc.sql("SELECT " + COLS + " FROM job_definition"
                        + " WHERE enabled = 1 AND missing = 0 ORDER BY job_name")
                .query(JobDefinitionDao::map).list();
    }

    public JobDefinitionRow findByName(String jobName) {
        return jdbc.sql("SELECT " + COLS + " FROM job_definition WHERE job_name = :n")
                .param("n", jobName)
                .query(JobDefinitionDao::map).optional().orElse(null);
    }

    /**
     * 启动时把代码里的声明写进表。**这里有一条会静默伤人的规则：**
     *
     * <p>{@code cron} / {@code enabled} / {@code timeout_sec} / {@code lock_at_most_sec}
     * / {@code params} <b>只在首次 INSERT 时用代码的值；之后永不覆盖</b>。
     * 它们入库即归运营。
     *
     * <p>如果这里图省事写成「每次启动 upsert 全部列」，运营在页面上改的 cron
     * 会在下次发版时被悄悄冲掉 —— 没有报错、没有日志，只是某天起任务又按老点跑了。
     * 这种缺陷不会有人报障，只会有人某天问「为什么它又变回三点了」。
     *
     * <p>反过来，{@code display_name} / {@code description} / {@code owner_module}
     * 是「只有代码知道」的，每次都更新成最新的。
     *
     * @return true 表示这次是新建（首次见到这个任务）
     */
    public boolean upsertFromCode(String jobName, JobDeclaration d, String target) {
        return upsertFromCode(jobName, d, target, d.enabled());
    }

    /**
     * @param enabledOnFirstInsert 首次入库时的开关。**只在 INSERT 时用** ——
     *                             之后 {@code enabled} 归运营，代码永不覆盖。
     *                             第一次启用整套调度时传 false：任务全部登记进表、
     *                             页面上看得见，但一个都不跑，由运营逐个打开。
     */
    public boolean upsertFromCode(String jobName, JobDeclaration d, String target,
                                  boolean enabledOnFirstInsert) {
        int updated = updateCodeOwnedColumns(jobName, d, target);
        if (updated > 0) {
            return false;
        }
        try {
            insertFromCode(jobName, d, target, enabledOnFirstInsert);
            return true;
        } catch (DuplicateKeyException e) {
            // 两个实例同时首启动。谁先插进去都行，本次改成更新即可。
            updateCodeOwnedColumns(jobName, d, target);
            return false;
        }
    }

    /** 只更新「只有代码知道」的列。**这个方法里出现 cron 或 enabled 就是 bug。** */
    private int updateCodeOwnedColumns(String jobName, JobDeclaration d, String target) {
        return jdbc.sql("""
                        UPDATE job_definition
                           SET display_name = :displayName,
                               description  = :description,
                               owner_module = :ownerModule,
                               handler_name = :handlerName,
                               target       = :target,
                               missing      = 0
                         WHERE job_name = :jobName AND source = 'CODE'
                        """)
                .param("displayName", d.displayName())
                .param("description", d.description())
                .param("ownerModule", d.ownerModule())
                .param("handlerName", d.handlerName())
                .param("target", target)
                .param("jobName", jobName)
                .update();
    }

    private void insertFromCode(String jobName, JobDeclaration d, String target, boolean enabled) {
        jdbc.sql("""
                        INSERT INTO job_definition
                            (job_name, display_name, description, handler_name, target,
                             cron, enabled, timeout_sec, lock_at_most_sec,
                             manual_trigger, log_every_run, source, missing, owner_module)
                        VALUES
                            (:jobName, :displayName, :description, :handlerName, :target,
                             :cron, :enabled, :timeoutSec, :lockAtMostSec,
                             :manualTrigger, :logEveryRun, 'CODE', 0, :ownerModule)
                        """)
                .param("jobName", jobName)
                .param("displayName", d.displayName())
                .param("description", d.description())
                .param("handlerName", d.handlerName())
                .param("target", target)
                .param("cron", d.defaultCron())
                .param("enabled", enabled ? 1 : 0)
                .param("timeoutSec", d.timeoutSec())
                .param("lockAtMostSec", d.lockAtMostSec())
                .param("manualTrigger", d.manualTrigger() ? 1 : 0)
                .param("logEveryRun", d.logEveryRun() ? 1 : 0)
                .param("ownerModule", d.ownerModule())
                .update();
    }

    /**
     * 代码里已经不存在的任务，标出来而**不是删掉**。
     *
     * <p>静默消失比留着危险：运营会以为它还在跑。留一行标着「代码里已不存在」，
     * 至少有人能发现「这个任务怎么不见了」。
     *
     * <p>只动 {@code source='CODE'} 的行 —— 运营自己建的任务与代码无关。
     */
    public int markMissingExcept(List<String> liveHandlerNames) {
        if (liveHandlerNames.isEmpty()) {
            return jdbc.sql("UPDATE job_definition SET missing = 1 WHERE source = 'CODE' AND missing = 0")
                    .update();
        }
        return jdbc.sql("""
                        UPDATE job_definition SET missing = 1
                         WHERE source = 'CODE' AND missing = 0
                           AND handler_name NOT IN (:names)
                        """)
                .param("names", liveHandlerNames)
                .update();
    }

    // ── 以下是运营端的写入口。每一个都记 updated_by ——「谁把这个任务关了」必须查得到 ──

    public int updateCron(String jobName, String cron, String operator) {
        return jdbc.sql("UPDATE job_definition SET cron = :cron, updated_by = :by WHERE job_name = :n")
                .param("cron", cron).param("by", operator).param("n", jobName).update();
    }

    /**
     * 运营点了「立即执行」：记下请求时刻。
     *
     * <p><b>不直接跑</b> —— 运营端与 worker 之间不通信（见 V2 迁移的注释）。
     * worker 下一轮轮询看到它比 {@code last_triggered_at} 新就跑一次。
     *
     * @return 是否记下了（false = 没有这个任务，或它不允许手动触发）
     */
    public boolean requestTrigger(String jobName, LocalDateTime at, String operator) {
        return jdbc.sql("""
                        UPDATE job_definition SET trigger_requested_at = :at, updated_by = :by
                         WHERE job_name = :n AND manual_trigger = 1 AND enabled = 1 AND missing = 0
                        """)
                .param("at", at).param("by", operator).param("n", jobName)
                .update() > 0;
    }

    /**
     * 有待处理的手动触发的任务。
     *
     * <p>比的是两个时间戳而不是清一个布尔标志：清标志那一步失败或进程被杀，
     * 这个任务就会**每轮都跑一次**，直到有人发现。比大小是幂等的。
     */
    public List<JobDefinitionRow> findTriggerRequested() {
        return jdbc.sql("SELECT " + COLS + " FROM job_definition"
                        + " WHERE trigger_requested_at IS NOT NULL"
                        + " AND (last_triggered_at IS NULL OR trigger_requested_at > last_triggered_at)"
                        + " AND enabled = 1 AND missing = 0")
                .query(JobDefinitionDao::map).list();
    }

    /** 手动触发已受理：把水位推上去。**跑之前推**，避免跑挂了下一轮又跑。 */
    public void markTriggered(String jobName, LocalDateTime at) {
        jdbc.sql("UPDATE job_definition SET last_triggered_at = :at WHERE job_name = :n")
                .param("at", at).param("n", jobName).update();
    }

    public int setEnabled(String jobName, boolean enabled, String operator) {
        return jdbc.sql("UPDATE job_definition SET enabled = :e, updated_by = :by WHERE job_name = :n")
                .param("e", enabled ? 1 : 0).param("by", operator).param("n", jobName).update();
    }
}
