package ai.neargo.job.engine;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.job.store.JobDefinitionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 每一轮轮询做三件事：**问声明 → 对齐注册表 → 受理手动触发**。
 */
public class JobSyncService {

    private static final Logger log = LoggerFactory.getLogger(JobSyncService.class);

    private final JobDeclarationSource source;
    private final JobDefinitionDao definitions;
    private final JobRegistry registry;
    private final JobWorkerProperties props;

    public JobSyncService(JobDeclarationSource source, JobDefinitionDao definitions,
                   JobRegistry registry, JobWorkerProperties props) {
        this.source = source;
        this.definitions = definitions;
        this.registry = registry;
        this.props = props;
    }

    public void syncOnce() {
        refreshDeclarations();
        JobRegistry.SyncReport r = registry.sync();
        if (r.added() + r.rescheduled() + r.removed() + r.invalid() > 0) {
            log.info("调度已对齐：新增 {}、改期 {}、移除 {}、非法 cron {}，当前共 {} 个",
                    r.added(), r.rescheduled(), r.removed(), r.invalid(), r.total());
        }
        runTriggerRequests();
    }

    /**
     * 受理运营端点下的「立即执行」。
     *
     * <p><b>运营端与 worker 不直接通信</b>，中间隔着 {@code job_definition} 的两列时间戳：
     * 运营写 {@code trigger_requested_at}，worker 在这里把 {@code last_triggered_at}
     * 推到同一时刻。比大小而不是清标志 —— 清标志那一步失败或进程被杀，
     * 这个任务会每轮都跑一次，直到有人发现。
     *
     * <p>三处顺序上的讲究：
     * <ol>
     *   <li><b>先推水位再跑</b>。反过来的话，任务跑挂（或 worker 在跑的中途被重启）
     *       下一轮会再跑一次，而运营只点了一次。代价是「推完水位、还没排上就崩了」
     *       会丢掉这一次触发 —— <b>丢一次手动触发，好过一个自己重复的执行循环</b>。</li>
     *   <li>水位取<b>行里的 {@code trigger_requested_at}</b>，不取 {@code now()}。
     *       取 now 会把「读出这批行之后、推水位之前」新来的请求一起吞掉；
     *       取行里的值，那条新请求下一轮照样捞得到。</li>
     *   <li>{@link JobRegistry#triggerNow} 是<b>异步排一次</b>，不在这里同步跑。
     *       同步跑的话一个慢任务会把整轮轮询堵住，声明与 cron 变更跟着停摆。</li>
     * </ol>
     *
     * <p>取不到就整轮跳过，理由与 {@link #refreshDeclarations} 相同：库抖一下
     * 不该让调度对齐这件事也跟着失败。
     */
    private void runTriggerRequests() {
        List<JobDefinitionRow> pending;
        try {
            pending = definitions.findTriggerRequested(props.effectiveTargets());
        } catch (RuntimeException e) {
            log.warn("查手动触发请求失败，本轮跳过 异常={}", e.getClass().getSimpleName());
            return;
        }
        for (JobDefinitionRow d : pending) {
            try {
                definitions.markTriggered(d.jobName(), d.triggerRequestedAt());
            } catch (RuntimeException e) {
                // 水位没推上去就不能跑 —— 否则下一轮还会捞到它，变成反复执行
                log.error("受理手动触发失败，不执行 job={} 异常={}",
                        d.jobName(), e.getClass().getSimpleName());
                continue;
            }
            log.info("手动触发 job={} 请求于 {}", d.jobName(), d.triggerRequestedAt());
            registry.triggerNow(d.jobName());
        }
    }

    /**
     * 去每个业务系统问「你代码里声明了哪些任务」，写进 {@code job_definition}。
     *
     * <p><b>取不到就整个 target 跳过，绝不按「空清单」处理。</b>
     * 空清单会被 {@link JobDefinitionDao#markMissingExcept} 理解成
     * 「这些任务在代码里都没了」，于是把它们全标成 missing、**全部停止调度** ——
     * 而触发条件仅仅是业务系统在发布中重启了一下。
     * 一次网络抖动换来全线停摆，这是本类里最危险的一条路径。
     */
    private void refreshDeclarations() {
        /*
         * **进程内形态没有 target 的概念。**
         *
         * 独立 worker 要按 target 分别去问各个业务系统；而跑在业务实例内时，
         * 声明就在同一个进程里，问谁都是问自己。配置里 targets 为空时用一个占位名走一遍 ——
         * 不这么做的话循环一次都不进，表现是「同步跑了、没报错、job_definition 还是空的」，
         * 而运营页面上什么都看不到。
         */
        for (String target : props.effectiveTargets()) {
            List<JobDeclaration> declarations;
            try {
                declarations = source.fetch(target);
            } catch (RuntimeException e) {
                log.warn("取任务声明失败，保持现状 target={} 异常={}",
                        target, e.getClass().getSimpleName());
                continue;
            }
            if (declarations.isEmpty()) {
                // 业务系统真的一个任务都没有，与「问不到」不同，但同样不该触发全线 missing。
                // 真要下线全部任务，运营在页面上关就是了 —— 那是有人做的决定，看得见。
                log.warn("target={} 返回了空的任务声明，跳过（不按「代码里都没了」处理）", target);
                continue;
            }
            List<String> live = new ArrayList<>();
            for (JobDeclaration d : declarations) {
                // job_name 首次等于 handler_name。运营将来可以用同一个 handler 再建别的实例，
                // 那些行的 source=MANUAL，代码永远不碰
                boolean created = definitions.upsertFromCode(d.handlerName(), d, target,
                        !props.isStartDisabled() && d.enabled());
                if (created) {
                    log.info("发现新任务 job={} 模块={} 初始状态={}", d.handlerName(), d.ownerModule(),
                            props.isStartDisabled() ? "停（等运营打开）" : "开");
                }
                live.add(d.handlerName());
            }
            // **只标自己这个 target 下的** —— 不限定的话两个 worker 会互相标对方
            int missing = definitions.markMissingExcept(live, java.util.Set.of(target));
            if (missing > 0) {
                log.warn("{} 个任务在代码里已不存在，已标记（**没有删行**，静默消失比留着危险）", missing);
            }
        }
    }
}
