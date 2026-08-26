package ai.neargo.job.worker;

import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.job.store.JobLogDao;
import ai.neargo.job.store.JobRunDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;

/**
 * worker 的装配。
 *
 * <p><b>轮询用调度器自己排，不用 {@code @Scheduled}。</b>
 * 一个把「运行时可配」当卖点的模块，自己的心跳却写死在注解里，
 * 那是一处说不通的地方 —— 而且轮询间隔真需要临时调大时（比如库压力大），
 * 注解版只能改配置重启。
 */
@Configuration
public class JobWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerConfig.class);

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler jobTaskScheduler(JobWorkerProperties props) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.getPoolSize());
        scheduler.setThreadNamePrefix("job-");
        // 关进程时给在跑的任务留时间。杀在半路的代价不是丢一轮，
        // 而是 job_run 停在 running=1，下次启动看上去像「有个任务卡住了」
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    HttpBusinessClient httpBusinessClient(JobWorkerProperties props) {
        return new HttpBusinessClient(props);
    }

    @Bean
    JobRunner jobRunner(HttpBusinessClient client, JobRunDao runs, JobLogDao logs,
                        JobWorkerProperties props) {
        return new JobRunner(client, runs, logs, props);
    }

    @Bean
    JobRegistry jobRegistry(ThreadPoolTaskScheduler jobTaskScheduler, JobDefinitionDao definitions,
                            JobRunDao runs, JobRunner jobRunner) {
        return new JobRegistry(jobTaskScheduler, definitions, runs, jobRunner);
    }

    @Bean
    JobSyncService jobSyncService(HttpBusinessClient client, JobDefinitionDao definitions,
                                  JobRegistry registry, JobWorkerProperties props) {
        return new JobSyncService(client, definitions, registry, props);
    }

    @Bean
    LogPurge logPurge(JobLogDao logs, JobWorkerProperties props) {
        return new LogPurge(logs, props);
    }

    /**
     * 启动后开始轮询。
     *
     * <p>用 {@code ApplicationRunner} 而不是 {@code @PostConstruct}：
     * 后者在 Bean 初始化阶段就跑，那时数据源与 Flyway 未必就绪，
     * 而失败会表现为「启动报了个看不懂的错」。
     */
    @Bean
    ApplicationRunner jobWorkerBootstrap(ThreadPoolTaskScheduler jobTaskScheduler,
                                         ObjectProvider<JobSyncService> sync,
                                         LogPurge logPurge,
                                         JobWorkerProperties props) {
        return args -> {
            Duration interval = props.getPollInterval();
            jobTaskScheduler.scheduleWithFixedDelay(
                    () -> safely("轮询配置", () -> sync.getObject().syncOnce()),
                    Instant.now(), interval);
            // 日志清理一天一次就够。它只碰 job 库，不占业务系统任何资源
            jobTaskScheduler.scheduleWithFixedDelay(
                    () -> safely("清理执行日志", logPurge::purge),
                    Instant.now().plusSeconds(60), Duration.ofDays(1));
            log.info("定时任务调度器已启动 instance={} 轮询={}s targets={}",
                    props.getInstance(), interval.toSeconds(), props.getTargets().keySet());
        };
    }

    /**
     * 周期任务里抛出的异常会让 {@code ScheduledFuture} 被取消 ——
     * <b>从此再也不跑，而且没有任何地方会说它被取消了</b>。
     * 心跳本身尤其不能这样死掉：它一死，整个 worker 就停在最后一次的配置上，
     * 表面上还活着。
     */
    private static void safely(String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.error("{}失败，本轮跳过 异常={}", what, e.getClass().getSimpleName(), e);
        }
    }
}
