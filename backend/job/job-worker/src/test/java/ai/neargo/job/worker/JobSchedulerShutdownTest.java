package ai.neargo.job.worker;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.job.engine.JobWorkerProperties;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 调度器的关闭顺序。2026-09-24 每次重启 worker 都刷出几条 {@code CannotGetJdbcConnectionException}：
 * 连接池先关了，而关闭那 30 秒里到点的 cron 仍被触发。两处修正各钉一条。
 */
@DisplayName("调度器关闭")
class JobSchedulerShutdownTest {

    @Test
    @DisplayName("★★★ 关闭时排着队、还没开始的任务不再执行 —— 否则它们会撞上已经关掉的连接池")
    void queuedTasksDoNotRunAfterShutdown() throws InterruptedException {
        ThreadPoolTaskScheduler scheduler = new JobWorkerConfig().jobTaskScheduler(new JobWorkerProperties());
        AtomicBoolean ran = new AtomicBoolean(false);
        scheduler.schedule(() -> ran.set(true), Instant.now().plusMillis(500));

        scheduler.shutdown();
        Thread.sleep(900);

        assertThat(ran).as("关闭之后到点的任务被执行了").isFalse();
    }

    @Test
    @DisplayName("★★ 调度器依赖 jobDataSource —— 于是它先于连接池被销毁，在跑的任务收尾时连接池还在")
    void schedulerIsDestroyedBeforeTheDataSource() throws NoSuchMethodException {
        Method m = JobWorkerConfig.class.getDeclaredMethod("jobTaskScheduler", JobWorkerProperties.class);
        assertThat(m.getAnnotation(DependsOn.class)).isNotNull();
        assertThat(m.getAnnotation(DependsOn.class).value()).contains("jobDataSource");
    }
}
