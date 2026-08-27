package ai.neargo.job.engine;

import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import ai.neargo.job.store.JobDefinitionRow;
import ai.neargo.job.store.JobLogDao;
import ai.neargo.job.store.JobRunDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 跑一轮：落库 → 发出去 → 记结果。**这是 worker 里唯一有状态机的地方。**
 */
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobInvoker invoker;
    private final JobRunDao runs;
    private final JobLogDao logs;
    private final JobWorkerProperties props;

    public JobRunner(JobInvoker invoker, JobRunDao runs, JobLogDao logs, JobWorkerProperties props) {
        this.invoker = invoker;
        this.runs = runs;
        this.logs = logs;
        this.props = props;
    }

    public void run(JobDefinitionRow def, TriggerType trigger) {
        String runId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();
        long t0 = System.nanoTime();

        runs.markStarted(def.jobName(), runId, startedAt);

        // **高频任务不落 RUNNING 行。** 一个 10 分钟级任务一年 5 万行，
        // 若每轮先落一行 RUNNING 再更新，日志表会长成本库最大的那张。
        // 代价：这类任务中途被杀就没有日志痕迹 —— 但 job_run.running 仍然标着，
        // 那就是它的痕迹，而且那一行永远只有一条，不会膨胀。
        boolean logEveryRun = def.logEveryRun();
        if (logEveryRun) {
            logs.insertStarted(runId, def.jobName(), trigger, yesterdayAsBizDate(), startedAt,
                    props.getInstance());
        }

        InvokeOutcome outcome = invokeWithRetry(def, runId, trigger);
        long durationMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

        runs.markFinished(def.jobName(), outcome.status(), durationMs,
                outcome.detail(), outcome.error());

        if (logEveryRun) {
            logs.finish(runId, outcome.status(), LocalDateTime.now(), durationMs,
                    outcome.detail(), outcome.error(), outcome.httpStatus());
        } else if (shouldLogSparsely(def, outcome)) {
            // 只在**状态变化或失败**时补一行完整的
            logs.insertStarted(runId, def.jobName(), trigger, yesterdayAsBizDate(), startedAt,
                    props.getInstance());
            logs.finish(runId, outcome.status(), LocalDateTime.now(), durationMs,
                    outcome.detail(), outcome.error(), outcome.httpStatus());
        }

        if (outcome.status().countsAsFailure()) {
            log.warn("任务失败 job={} 用时={}ms error={}", def.jobName(), durationMs, outcome.error());
        }
    }

    /**
     * <b>只有 {@code UNREACHABLE} 才重试。</b>这条是整个执行器里最要紧的判断：
     *
     * <ul>
     *   <li>{@code UNREACHABLE} —— 连接就没建立，<b>可以确定业务侧什么都没跑</b>，重试安全</li>
     *   <li>{@code TIMEOUT} —— <b>业务侧多半正在跑</b>。重试等于让同一件事跑两遍，
     *       而它未必幂等（日结、关单都不是）。宁可记一条「结果未知」让人去看</li>
     *   <li>{@code FAILED} —— 跑了、失败了。重试是业务决定，不是调度器的决定</li>
     *   <li>{@code SKIPPED} —— 锁没抢到，说明上一轮还在跑，重试只会再被拒一次</li>
     * </ul>
     *
     * 把这四种混成「失败就重试」，代价是在最不该重复执行的场景下重复执行。
     */
    private InvokeOutcome invokeWithRetry(JobDefinitionRow def, String runId, TriggerType trigger) {
        Duration[] backoff = props.getRetryBackoff();
        InvokeOutcome last = null;
        for (int attempt = 0; attempt <= backoff.length; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(backoff[attempt - 1].toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return last == null ? InvokeOutcome.unreachable("Interrupted") : last;
                }
                log.info("重试调用 job={} 第 {} 次", def.jobName(), attempt);
            }
            JobInvocation in = new JobInvocation(runId,
                    attempt == 0 ? trigger : TriggerType.RETRY,
                    yesterdayAsBizDate(), JobParams.parse(def.jobName(), def.params()));
            last = invoker.invoke(def.target(), def.handlerName(), in, def.timeoutSec());
            if (last.status() != JobStatus.UNREACHABLE) {
                return last;
            }
        }
        return last;
    }

    /**
     * 稀疏日志的落行判据：**失败，或与上一轮的状态不同**。
     *
     * <p>一直成功的高频任务因此一行都不写；变红那一刻写一行，恢复那一刻再写一行 ——
     * 于是日志读起来正好是「状态变迁史」，而不是一堆「又成功了」。
     */
    private boolean shouldLogSparsely(JobDefinitionRow def, InvokeOutcome outcome) {
        if (outcome.status() != JobStatus.SUCCESS) {
            return true;
        }
        String previous = logs.lastStatusOf(def.jobName());
        return previous == null || !JobStatus.SUCCESS.name().equals(previous);
    }

    /**
     * 业务日期恒为<b>昨天</b>。
     *
     * <h2>为什么是昨天</h2>
     * <p>日结、对账在凌晨跑，算的永远是上一个自然日的账。给今天等于算了半天的账，
     * 而这种错不会报错 —— 它只会让数字对不上，然后有人花一天去找原因。
     * 不关心日期的任务忽略这个字段即可。
     *
     * <h2>为什么名字里写死「昨天」，而不是留一个参数</h2>
     * <p>原先的签名是 {@code bizDateFor(JobDefinitionRow def)} —— <b>收了 def 却一行没看</b>。
     * 一个长得像可配置、实际是常量的参数，比一个老实的常量更危险：
     * 下一个人会以为「配一下就能改」，而配了不生效且不报错。
     *
     * <p>真要按任务配（每小时的任务用今天、手动补跑指定某天），那是往
     * {@code job_definition} 加一列偏移量的事，届时这个方法会被替换掉 ——
     * <b>现在为一个还不存在的需求留参数，留下的只是一个说谎的签名。</b>
     * 当前 11 个任务没有一个真的读 bizDate。
     */
    private static LocalDate yesterdayAsBizDate() {
        return LocalDate.now().minusDays(1);
    }
}
