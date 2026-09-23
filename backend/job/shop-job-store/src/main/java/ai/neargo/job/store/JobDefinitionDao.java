package ai.neargo.job.store;

import ai.neargo.job.api.JobDeclaration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.Collection;
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
    /**
     * 该由<b>我这个 worker</b> 排期的任务。
     *
     * <p><b>必须按 target 过滤。</b>不过滤的话，一个 worker 会把别的业务系统的任务
     * 也排上，而它解析不出对方的地址 —— 每一轮都回 UNREACHABLE，而那看起来像
     * 「业务系统挂了」。{@code target} 这一列存在的意义就是支持多个业务系统，
     * 查询却把它无视掉，等于假设永远只有一个 worker。
     */
    public List<JobDefinitionRow> findSchedulable(Collection<String> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLS + " FROM job_definition"
                        + " WHERE enabled = 1 AND missing = 0 AND target IN (:targets)"
                        + " ORDER BY job_name")
                .param("targets", targets)
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
        int updated = updateCodeOwnedColumns(jobName, d);
        if (updated > 0) {
            return false;
        }
        try {
            insertFromCode(jobName, d, target, enabledOnFirstInsert);
            return true;
        } catch (DuplicateKeyException e) {
            // 两个实例同时首启动。谁先插进去都行，本次改成更新即可。
            updateCodeOwnedColumns(jobName, d);
            return false;
        }
    }

    /**
     * 只更新「只有代码知道」的列。**这个方法里出现 cron 或 enabled 就是 bug。**
     *
     * <h2>2026-08-28 补进来的四列：超时、持锁、手动触发、是否每轮记日志</h2>
     * <p>它们此前<b>只在首次 INSERT 时写一次，之后谁都改不了</b>：代码改了不生效
     * （不在这条 UPDATE 里），运营端也没有改它们的入口。于是生产上 11 个任务的
     * {@code timeout_sec} 全是 60、{@code lock_at_most_sec} 从 180 到 1800 ——
     * 那是第一次插入时的值，冻在那里，而没有任何地方会说改代码没用。
     *
     * <p><b>归代码而不归运营</b>，理由与 cron 恰好相反：「这个任务最长跑多久」
     * 「要不要每轮记日志」是写这段逻辑的人才知道的事；而「几点跑」「开不开」
     * 是运营的决定。分界线是<b>谁掌握判断所需的信息</b>，不是谁改起来方便。
     *
     * <h2>2026-08-28 从这里拿掉的一列：target</h2>
     * <p>按同一条分界线，{@code target} 就不该在这儿 —— 它是「哪个业务系统提供这个
     * handler」，属于<b>部署拓扑</b>，由 worker 的配置决定，代码根本不知道。
     *
     * <p>放在这里的后果当天就在生产上出现了：两个 worker 都声明同一批 handler
     * （一个独立调度器 target=PLATFORM，一个手工起的进程没配 targets 退到 LOCAL），
     * 于是每 30 秒各写各的，<b>12 行的 target 来回翻</b>。翻到不属于自己那一侧时，
     * 任务既排不上、手动触发也会被错误的 worker 领走 —— 而没有任何报错。
     *
     * <p>现在只在 {@link #insertFromCode} 时写一次：<b>第一个见到这个 handler 的
     * worker 认领它</b>。之后要改是一次有意的动作（改库或将来给运营端加入口），
     * 而不是另一个 worker 轮询的副作用。
     *
     * <p>代价说清楚：handler 真的从一个业务系统挪到另一个时，旧 target 不会自动更新
     * —— 表现是旧 worker 把它标成「代码里已不存在」，新 worker 看不见它。
     * 那时需要人改一行。<b>比起悄悄来回翻，这个代价是划算的</b>：它至少看得见。
     */
    private int updateCodeOwnedColumns(String jobName, JobDeclaration d) {
        return jdbc.sql("""
                        UPDATE job_definition
                           SET display_name     = :displayName,
                               description      = :description,
                               owner_module     = :ownerModule,
                               handler_name     = :handlerName,
                               timeout_sec      = :timeoutSec,
                               lock_at_most_sec = :lockAtMostSec,
                               manual_trigger   = :manualTrigger,
                               log_every_run    = :logEveryRun,
                               missing          = 0
                         WHERE job_name = :jobName AND source = 'CODE'
                        """)
                .param("displayName", d.displayName())
                .param("description", d.description())
                .param("ownerModule", d.ownerModule())
                .param("handlerName", d.handlerName())
                .param("timeoutSec", d.timeoutSec())
                .param("lockAtMostSec", d.lockAtMostSec())
                .param("manualTrigger", d.manualTrigger() ? 1 : 0)
                .param("logEveryRun", d.logEveryRun() ? 1 : 0)
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
    /**
     * <p><b>只标自己这些 target 下的行。</b>不限定的话，两个 target 不同的 worker
     * 会互相把对方的任务标成「代码里已不存在」—— 各自都只认识自己那份声明。
     * 2026-08-28 生产上真的发生了：两个 worker 每 30 秒互标一次，
     * 日志里同一条 WARN 无限重复，而<b>恒响的告警就是噪声掩体</b>。
     */
    public int markMissingExcept(List<String> liveHandlerNames, Collection<String> targets) {
        if (targets.isEmpty()) {
            return 0;
        }
        if (liveHandlerNames.isEmpty()) {
            return jdbc.sql("UPDATE job_definition SET missing = 1"
                            + " WHERE source = 'CODE' AND missing = 0 AND target IN (:targets)")
                    .param("targets", targets)
                    .update();
        }
        return jdbc.sql("""
                        UPDATE job_definition SET missing = 1
                         WHERE source = 'CODE' AND missing = 0
                           AND target IN (:targets)
                           AND handler_name NOT IN (:names)
                        """)
                .param("names", liveHandlerNames)
                .param("targets", targets)
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
    /**
     * <p><b>同样只看自己 target 下的。</b>不限定的后果比前两处更直接：
     * 两个 worker 都会捞到同一条触发请求，谁先 {@link #markTriggered} 谁算受理 ——
     * 而如果受理的那个跑不了（target 对不上、解析不出地址），
     * <b>这一次触发就被吃掉了</b>：任务没跑，而页面上的「已排队」因为水位被推高
     * 而消失，看起来像已经跑完了。
     */
    public List<JobDefinitionRow> findTriggerRequested(Collection<String> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLS + " FROM job_definition"
                        + " WHERE trigger_requested_at IS NOT NULL"
                        + " AND (last_triggered_at IS NULL OR trigger_requested_at > last_triggered_at)"
                        + " AND enabled = 1 AND missing = 0 AND target IN (:targets)")
                .param("targets", targets)
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
