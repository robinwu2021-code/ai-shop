package ai.neargo.job.engine;

import ai.neargo.job.api.TriggerType;
import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.job.store.JobDefinitionRow;
import ai.neargo.job.store.JobRunDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 注册表 —— 把 {@code job_definition} 里的<b>期望状态</b>搬成真实的调度。
 *
 * <p><b>为什么不用 {@code @Scheduled}</b>：它的 cron 是启动时读一次的常量。
 * 要做到「页面上改了、不重启就生效」，只能自己持有 {@link ScheduledFuture}。
 * 换来的是开关、改 cron、立即触发、重启单个任务 ——
 * <b>这四件事本来就是同一个机制的四个动作</b>，一次全解决。
 *
 * <p>{@link #sync} 是幂等的：每轮轮询都拿全量期望状态来对齐，
 * 而不是记「上次改了什么」。**对齐比打补丁难写错** ——
 * 漏掉一种变化（比如「cron 改了同时又被关掉」）的补丁式实现，症状是某个任务卡在旧频率上，
 * 而没有任何地方会报错。
 */
public class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    private final TaskScheduler scheduler;
    private final JobDefinitionDao definitions;
    private final JobRunDao runs;
    private final JobRunner runner;
    private final JobWorkerProperties props;

    /** 已排上的任务：job_name → (当前用的 cron, future)。cron 一起存，才知道要不要重排。 */
    private final Map<String, Scheduled> scheduled = new ConcurrentHashMap<>();

    private record Scheduled(String cron, ScheduledFuture<?> future) {
    }

    public JobRegistry(TaskScheduler scheduler, JobDefinitionDao definitions, JobRunDao runs,
                       JobRunner runner, JobWorkerProperties props) {
        this.scheduler = scheduler;
        this.definitions = definitions;
        this.runs = runs;
        this.runner = runner;
        this.props = props;
    }

    /**
     * 把期望状态对齐到当前调度。
     *
     * @return 这一轮实际发生的变化，供日志与测试断言
     */
    public synchronized SyncReport sync() {
        // **只排自己 target 下的任务**：排了别人的，每一轮都会因为解析不出地址
        // 而回 UNREACHABLE，而那看起来像「业务系统挂了」
        List<JobDefinitionRow> wanted = definitions.findSchedulable(props.effectiveTargets());
        Set<String> wantedNames = new HashSet<>();
        int added = 0, rescheduled = 0, removed = 0, invalid = 0;

        for (JobDefinitionRow def : wanted) {
            wantedNames.add(def.jobName());
            if (!CronExpression.isValidExpression(def.cron())) {
                // 非法 cron 不能让整轮同步崩掉 —— 那会把**其它任务**一起拖下水。
                // 运营端在落库前也校验，这里是第二道：库可能被手工改过。
                log.error("cron 非法，跳过该任务 job={} cron={}", def.jobName(), def.cron());
                invalid++;
                continue;
            }
            Scheduled current = scheduled.get(def.jobName());
            if (current == null) {
                schedule(def);
                added++;
            } else if (!Objects.equals(current.cron(), def.cron())) {
                current.future().cancel(false);
                schedule(def);
                rescheduled++;
            }
        }

        for (String name : Set.copyOf(scheduled.keySet())) {
            if (!wantedNames.contains(name)) {
                // 关掉 = **取消调度**，不是让它空跑一趟。
                // 空跑的实现会在日志里留下一堆「跳过」，而运营看到的是「它还在跑」
                scheduled.remove(name).future().cancel(false);
                removed++;
            }
        }
        return new SyncReport(added, rescheduled, removed, invalid, scheduled.size());
    }

    private void schedule(JobDefinitionRow def) {
        CronTrigger trigger = new CronTrigger(def.cron());
        ScheduledFuture<?> future = scheduler.schedule(
                () -> runOne(def.jobName(), TriggerType.CRON), trigger);
        scheduled.put(def.jobName(), new Scheduled(def.cron(), future));
        nextRunAt(def).ifPresent(at -> runs.updateNextRunAt(def.jobName(), at));
    }

    /**
     * 每一轮都**重新读一次定义**，而不是用排程时那份。
     *
     * <p>否则运营改了 {@code timeout_sec} 之类的列，要等到下一次 cron 变化才生效 ——
     * 而那是一种「改了没反应，过阵子又自己好了」的行为，最难排查。
     */
    private void runOne(String jobName, TriggerType trigger) {
        JobDefinitionRow fresh = definitions.findByName(jobName);
        if (fresh == null || !fresh.enabled() || fresh.missing()) {
            return;
        }
        try {
            runner.run(fresh, trigger);
        } catch (RuntimeException e) {
            // 吞掉：抛出去会让 ScheduledFuture 被取消，**这个任务从此再也不跑**，
            // 而且没有任何地方会说它被取消了
            log.error("任务执行抛异常 job={} 异常={}", jobName, e.getClass().getSimpleName(), e);
        } finally {
            nextRunAt(fresh).ifPresent(at -> runs.updateNextRunAt(jobName, at));
        }
    }

    /** 运营端页面直接显示，省得人肉解 cron。 */
    private java.util.Optional<LocalDateTime> nextRunAt(JobDefinitionRow def) {
        if (def == null || !CronExpression.isValidExpression(def.cron())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(
                CronExpression.parse(def.cron()).next(LocalDateTime.now(ZoneId.systemDefault())));
    }

    /** 立即跑一次（运营点了「立即执行」）。**仍走业务侧的锁**，正在跑时会被拒。 */
    public void triggerNow(String jobName) {
        scheduler.schedule(() -> runOne(jobName, TriggerType.MANUAL), java.time.Instant.now());
    }

    /** 当前排上了哪些，测试与排查用。 */
    public Set<String> scheduledNames() {
        return Set.copyOf(scheduled.keySet());
    }

    public record SyncReport(int added, int rescheduled, int removed, int invalid, int total) {
    }
}
