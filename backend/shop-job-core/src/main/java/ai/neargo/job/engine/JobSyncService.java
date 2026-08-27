package ai.neargo.job.engine;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.store.JobDefinitionDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 每一轮轮询做两件事：**去业务系统问声明 → 把注册表对齐到库**。
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
        var targets = props.getTargets().isEmpty()
                ? java.util.List.of("LOCAL") : props.getTargets().keySet();
        for (String target : targets) {
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
                boolean created = definitions.upsertFromCode(d.handlerName(), d, target);
                if (created) {
                    log.info("发现新任务 job={} 模块={}", d.handlerName(), d.ownerModule());
                }
                live.add(d.handlerName());
            }
            int missing = definitions.markMissingExcept(live);
            if (missing > 0) {
                log.warn("{} 个任务在代码里已不存在，已标记（**没有删行**，静默消失比留着危险）", missing);
            }
        }
    }
}
