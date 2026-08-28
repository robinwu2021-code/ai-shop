package ai.neargo.shop.config;

import ai.neargo.job.api.JobStatus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.engine.InvokeOutcome;
import ai.neargo.job.engine.JobDeclarationSource;
import ai.neargo.job.engine.JobInvoker;
import ai.neargo.job.engine.JobRegistry;
import ai.neargo.job.engine.JobRunner;
import ai.neargo.job.engine.JobSyncService;
import ai.neargo.job.engine.JobWorkerProperties;
import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.job.store.JobLogDao;
import ai.neargo.job.store.JobRunDao;
import ai.neargo.shop.job.JobHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * <b>业务实例内的 worker</b>（J2/G2 阶段）：调度器跑在 shop-app 里，任务体直接进程内调。
 *
 * <p>与独立 worker（{@code shop-job}）**跑的是同一套引擎** —— 只有
 * {@link JobInvoker} 的实现不同：这里查进程内的 {@link JobHandlerRegistry}，
 * 那边发 HTTP。切换是换一个 bean，不是重写调度。
 *
 * <p>为什么先走这一步：14 个任务从上线至今<b>一次都没跑过</b>，
 * 第一次跑起来是**行为的净增加**，不是修复。先在业务实例内跑、看一周，
 * 再拆进程 —— 中间任何一步出问题都能只回退一层。
 *
 * <p><b>三个开关同时满足才启用</b>：{@code @Profile("worker")}、
 * {@code shop.job.enabled=true}，以及 {@code shop.job.registry.enabled}（默认 true）。
 * 生产今天前两个都不满足，所以这段代码一行都不执行。
 *
 * <h2>第三个开关是 2026-08-28 加的：{@code shop.job.registry.enabled}</h2>
 * <p>{@code shop.job.enabled} 此前一个人扛了三件事：
 * <ol>
 *   <li>任务体（各 {@code JobHandler} bean）存不存在</li>
 *   <li>job 库的数据源与 DAO 装不装</li>
 *   <li><b>本进程要不要参与调度</b> —— 也就是要不要写 {@code job_definition}、要不要排期</li>
 * </ol>
 *
 * <p>三件事绑在一起，就没法表达「我只想跑其中一个任务，别碰共享的注册表」。
 * 当天生产上真的需要这个：一个一次性的进销存搬运进程必须开
 * {@code shop.job.enabled=true}（否则 {@code InventoryBackfillJob} 这个 bean
 * 根本不存在），于是它<b>连带把进程内调度器也带了起来</b> —— 那个调度器没配
 * {@code targets}，退到占位名 {@code LOCAL}，把生产 job 库 12 行的 {@code target}
 * 每 30 秒覆写一次，独立调度器因此一个任务都排不上。
 *
 * <p>而当时唯一想得到的建议「加 {@code --shop.job.enabled=false}」是错的：
 * 那会把它要跑的那个任务一起关掉。<b>能想到的唯一办法是错的，说明缺的是一个开关，
 * 不是使用者不小心。</b>
 *
 * <p>拆开之后：一次性 worker 加 {@code --shop.job.registry.enabled=false}，
 * 任务体照常有、{@code @Scheduled} 照常跑，只是不写共享注册表。
 */
@Configuration
@EnableConfigurationProperties(JobWorkerProperties.class)
@Profile("worker")
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@ConditionalOnProperty(name = "shop.job.registry.enabled", havingValue = "true", matchIfMissing = true)
public class InProcessJobConfig {

    private static final Logger log = LoggerFactory.getLogger(InProcessJobConfig.class);

    /**
     * 进程内调用：查注册表拿到 handler，直接调。
     *
     * <p>失败的三种形态与 HTTP 那边**对齐**（见 {@code InvokeOutcome} 的类注释）：
     * 找不到 handler 记 FAILED（代码里删了但表里还留着），
     * 抛异常记 FAILED（业务真的炸了），业务返回什么就是什么。
     * <b>这里不会有 UNREACHABLE / TIMEOUT</b> —— 进程内调用没有那两种失败。
     */
    @Bean
    JobInvoker localJobInvoker(JobHandlerRegistry handlers) {
        return (target, handlerName, invocation, timeoutSec) -> handlers.find(handlerName)
                .map(h -> invoke(h, invocation))
                .orElseGet(() -> {
                    log.error("任务 {} 在代码里不存在，但表里还留着 —— 它会一直「跑」却什么也不做",
                            handlerName);
                    return InvokeOutcome.of(JobStatus.FAILED,
                            "代码里没有这个 handler", "HandlerNotFound", null);
                });
    }

    private static InvokeOutcome invoke(JobHandler handler, JobInvocation invocation) {
        try {
            JobResult r = handler.run(invocation);
            return InvokeOutcome.of(r.status(), r.detail(), r.error(), null);
        } catch (RuntimeException e) {
            // 抛异常与「返回 FAILED」在排查时是两件事，所以 error 里放异常类名。
            // 不往外抛：抛出去会让 ScheduledFuture 被取消，那个任务**从此再也不跑**
            log.error("任务 {} 抛异常", handler.name(), e);
            return InvokeOutcome.of(JobStatus.FAILED,
                    "任务抛异常", e.getClass().getSimpleName(), null);
        }
    }

    /** 声明源：进程内直接从注册表拿，不必像独立 worker 那样去问业务系统。 */
    @Bean
    JobDeclarationSource localDeclarationSource(JobHandlerRegistry handlers) {
        return target -> handlers.declarations();
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler inProcessJobScheduler(JobWorkerProperties props) {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(props.getPoolSize());
        s.setThreadNamePrefix("job-");
        // 关进程时给在跑的任务留时间：杀在半路会让 job_run 停在 running=1，
        // 下次启动看上去像「有个任务卡住了」
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        s.initialize();
        return s;
    }

    @Bean
    JobRunner inProcessJobRunner(JobInvoker localJobInvoker, JobRunDao runs, JobLogDao logs,
                                 JobWorkerProperties props) {
        return new JobRunner(localJobInvoker, runs, logs, props);
    }

    @Bean
    JobRegistry inProcessJobRegistry(ThreadPoolTaskScheduler inProcessJobScheduler,
                                     JobDefinitionDao definitions, JobRunDao runs,
                                     JobRunner inProcessJobRunner, JobWorkerProperties props) {
        return new JobRegistry(inProcessJobScheduler, definitions, runs, inProcessJobRunner, props);
    }

    @Bean
    JobSyncService inProcessJobSync(JobDeclarationSource localDeclarationSource,
                                    JobDefinitionDao definitions, JobRegistry inProcessJobRegistry,
                                    JobWorkerProperties props) {
        return new JobSyncService(localDeclarationSource, definitions, inProcessJobRegistry, props);
    }

    /*
     * job 库的三个 DAO **不在这里声明** —— JobStoreConfig 是自动配置，
     * 引了 shop-job-store 且 shop.job.enabled=true 就已经装好了（用它自己的 jobJdbcClient，
     * 指向独立的 job 库）。在这里再声明一遍会指向平台库，而那是另一个库。
     */

    @Bean
    ApplicationRunner inProcessJobBootstrap(ThreadPoolTaskScheduler inProcessJobScheduler,
                                            JobSyncService inProcessJobSync,
                                            JobWorkerProperties props) {
        return args -> {
            inProcessJobScheduler.scheduleWithFixedDelay(
                    () -> {
                        try {
                            inProcessJobSync.syncOnce();
                        } catch (RuntimeException e) {
                            // 心跳里抛出去会让这条周期任务被取消 ——
                            // 它一死，整个调度就停在最后一次的配置上，而表面上还活着
                            log.error("任务配置轮询失败，本轮跳过 异常={}",
                                    e.getClass().getSimpleName(), e);
                        }
                    },
                    Instant.now(), props.getPollInterval());
            log.info("业务实例内的定时任务调度已启动 轮询={}s", props.getPollInterval().toSeconds());
        };
    }
}
