package ai.neargo.shop.portal.ops;

import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.job.store.JobDefinitionRow;
import ai.neargo.job.store.JobLogDao;
import ai.neargo.job.store.JobLogRow;
import ai.neargo.job.store.JobRunDao;
import ai.neargo.job.store.JobRunRow;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 平台端 · 定时任务。**这个系统的 17 个定时任务，至今没有任何地方能看见它们。**
 *
 * <p>生产上 {@code sys_job_run} 0 行、{@code shedlock} 0 行 —— 因为跑的是
 * {@code api,ops} 而任务全挂在 {@code worker} 上。「该跑的没跑」这件事，
 * 在这个页面出现之前没有任何人会发现。
 *
 * <h2>读库，不与 worker 通信</h2>
 * worker 挂了的时候，页面仍然要能显示「最后一次跑是 2 小时前」——
 * <b>那正是最需要看的时刻</b>。若页面向 worker 要数据，worker 一挂页面就是空白，
 * 等于把最关键的那次故障变成了盲区。
 *
 * <p>代价是「立即执行」也要经过库（写 {@code trigger_requested_at}，
 * worker 下一轮轮询捡起）—— 最长一个轮询周期后动，运维上与「立刻」没有区别。
 *
 * <h2>读写两个码</h2>
 * 排查问题的人比能停任务的人多得多：一个任务出事时，先来看的往往是被它影响到的
 * 那条业务线的人，而他们不该有权把它停掉。
 */
@Profile("ops")
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@RestController
public class OpsJobController {

    /** 日志一页 50 条：它是排查时一屏一屏翻的东西，不是给人分页点的。 */
    private static final int DEFAULT_LOG_SIZE = 50;

    private final JobDefinitionDao definitions;
    private final JobRunDao runs;
    private final JobLogDao logs;

    public OpsJobController(JobDefinitionDao definitions, JobRunDao runs, JobLogDao logs) {
        this.definitions = definitions;
        this.runs = runs;
        this.logs = logs;
    }

    /**
     * 一行 = 一个任务的**定义 + 当前状态**，两张表在这里合。
     *
     * <p>前端不该为了一屏数据发两次请求再自己 join —— 那样「任务有定义但从没跑过」
     * 这种状态就要靠前端拼，而它恰恰是今天最常见的状态（17 个任务一次都没跑过）。
     */
    @GetMapping("/ops/jobs")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_JOB_READ + "')")
    public List<JobVO> list() {
        Map<String, JobRunRow> byName = new java.util.HashMap<>();
        for (JobRunRow r : runs.findAll()) {
            byName.put(r.jobName(), r);
        }
        List<JobVO> out = new ArrayList<>();
        for (JobDefinitionRow d : definitions.findAll()) {
            out.add(JobVO.of(d, byName.get(d.jobName())));
        }
        return out;
    }

    @GetMapping("/ops/jobs/{name}")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_JOB_READ + "')")
    public JobVO detail(@PathVariable String name) {
        return JobVO.of(require(name), runs.findByName(name));
    }

    @GetMapping("/ops/jobs/{name}/logs")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_JOB_READ + "')")
    public List<JobLogRow> logs(@PathVariable String name,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "0") int size) {
        int limit = size > 0 ? Math.min(size, 200) : DEFAULT_LOG_SIZE;
        return logs.findByJob(name, limit, Math.max(0, page - 1) * limit);
    }

    @PostMapping("/ops/jobs/{name}/enable")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_JOB_MANAGE + "')")
    public JobVO enable(@PathVariable String name) {
        return setEnabled(name, true);
    }

    @PostMapping("/ops/jobs/{name}/disable")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_JOB_MANAGE + "')")
    public JobVO disable(@PathVariable String name) {
        return setEnabled(name, false);
    }

    /**
     * 改 cron。**落库前先校验表达式**。
     *
     * <p>不校验的话，运营看到的是「改成功了」，而 worker 在下一轮轮询时注册失败 ——
     * 那个任务从此不再被排上，但页面上它还开着、还有下次执行时间。
     */
    @PutMapping("/ops/jobs/{name}/cron")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_JOB_MANAGE + "')")
    public JobVO updateCron(@PathVariable String name, @RequestBody CronReq req) {
        require(name);
        String cron = req == null ? null : req.cron();
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        definitions.updateCron(name, cron, operator());
        return detail(name);
    }

    /**
     * 立即执行一次。
     *
     * <p>{@code manual_trigger=0} 的任务返回 403 而**不是静默忽略** ——
     * 静默忽略的话运营会一直点，而页面上什么都不发生。
     * 秒级任务不给这个按钮（它们本来就一直在跑）。
     */
    @PostMapping("/ops/jobs/{name}/trigger")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_JOB_MANAGE + "')")
    public JobVO trigger(@PathVariable String name) {
        JobDefinitionRow d = require(name);
        if (!d.manualTrigger() || !d.enabled() || d.missing()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        definitions.requestTrigger(name, LocalDateTime.now(), operator());
        return detail(name);
    }

    private JobVO setEnabled(String name, boolean enabled) {
        require(name);
        definitions.setEnabled(name, enabled, operator());
        return detail(name);
    }

    private JobDefinitionRow require(String name) {
        JobDefinitionRow d = definitions.findByName(name);
        if (d == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return d;
    }

    /** 「谁把这个任务关了」必须查得到 —— 每个写操作都记。 */
    private static String operator() {
        return SecurityUtils.currentUser().map(u -> u.userNo()).orElse("-");
    }

    public record CronReq(String cron) {
    }

    /**
     * 页面上一行。
     *
     * @param lastStatus          从没跑过时为 null —— 这是今天 17 个任务的**普遍状态**，
     *                            前端要把它显示成「从未执行」而不是空白
     * @param consecutiveFailures 只统计 FAILED；SKIPPED / TIMEOUT / UNREACHABLE 都不算
     * @param triggerPending      运营点过「立即执行」但 worker 还没捡起来。
     *                            没有这一格的话，点完按钮页面毫无反应，人会以为没点上
     */
    public record JobVO(
            String jobName, String displayName, String description, String ownerModule,
            String cron, boolean enabled, boolean missing, boolean manualTrigger,
            LocalDateTime lastRunAt, String lastStatus, Long durationMs, String detail,
            String error, int consecutiveFailures, long runCount, LocalDateTime nextRunAt,
            boolean running, boolean triggerPending, String updatedBy) {

        static JobVO of(JobDefinitionRow d, JobRunRow r) {
            Optional<JobRunRow> run = Optional.ofNullable(r);
            boolean pending = d.triggerRequestedAt() != null
                    && (d.lastTriggeredAt() == null
                        || d.triggerRequestedAt().isAfter(d.lastTriggeredAt()));
            return new JobVO(d.jobName(), d.displayName(), d.description(), d.ownerModule(),
                    d.cron(), d.enabled(), d.missing(), d.manualTrigger(),
                    run.map(JobRunRow::lastRunAt).orElse(null),
                    run.map(JobRunRow::lastStatus).orElse(null),
                    run.map(JobRunRow::durationMs).orElse(null),
                    run.map(JobRunRow::detail).orElse(null),
                    run.map(JobRunRow::error).orElse(null),
                    run.map(JobRunRow::consecutiveFailures).orElse(0),
                    run.map(JobRunRow::runCount).orElse(0L),
                    run.map(JobRunRow::nextRunAt).orElse(null),
                    run.map(JobRunRow::running).orElse(false),
                    pending, d.updatedBy());
        }
    }
}
