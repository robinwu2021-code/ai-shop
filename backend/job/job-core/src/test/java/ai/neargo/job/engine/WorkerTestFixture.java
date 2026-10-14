package ai.neargo.job.engine;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.store.*;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 各用例共用的装配：一个新 H2 库 + 三个 DAO + 一个可编程的假业务系统。 */
class WorkerTestFixture {

    final JdbcClient jdbc = JobStoreTestSupport.freshDatabase();
    final JobDefinitionDao definitions = new JobDefinitionDao(jdbc);
    final JobRunDao runs = new JobRunDao(jdbc);
    final JobLogDao logs = new JobLogDao(jdbc);
    final JobWorkerProperties props = new JobWorkerProperties();

    WorkerTestFixture() {
        props.setInstance("test-worker");
        props.setTargets(new java.util.LinkedHashMap<>(Map.of("PLATFORM", "http://unused")));
        // 测试里不要真的退避等待，否则一条用例要跑一分钟
        props.setRetryBackoff(new java.time.Duration[]{
                java.time.Duration.ZERO, java.time.Duration.ZERO, java.time.Duration.ZERO});
    }

    static JobDeclaration decl(String handler, String cron) {
        return JobDeclaration.daily(handler, "中文名-" + handler, "说明", "shop-test", cron);
    }

    /** 可编程的假业务系统：按脚本依次返回结果，并记下收到了几次、每次的 triggerType。 */
    static final class FakeBusiness implements JobInvoker, JobDeclarationSource {
        private final List<InvokeOutcome> script = new ArrayList<>();
        final List<JobInvocation> received = new ArrayList<>();
        List<JobDeclaration> declarations = List.of();
        RuntimeException fetchFailure;
        private int cursor;

        FakeBusiness returning(InvokeOutcome... outcomes) {
            script.addAll(List.of(outcomes));
            return this;
        }

        @Override
        public InvokeOutcome invoke(String target, String handler, JobInvocation in, int timeoutSec) {
            received.add(in);
            InvokeOutcome o = script.get(Math.min(cursor, script.size() - 1));
            cursor++;
            return o;
        }

        @Override
        public List<JobDeclaration> fetch(String target) {
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            return declarations;
        }
    }

    /** 直接跑的调度器替身：schedule 只记下 Runnable，由用例决定什么时候触发。 */
    static final class ManualScheduler implements org.springframework.scheduling.TaskScheduler {
        final Map<Object, Runnable> tasks = new java.util.LinkedHashMap<>();
        final AtomicReference<Runnable> lastImmediate = new AtomicReference<>();

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable task, org.springframework.scheduling.Trigger trigger) {
            Object handle = new Object();
            tasks.put(handle, task);
            return new FakeFuture(() -> tasks.remove(handle));
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, java.time.Instant startTime) {
            lastImmediate.set(task);
            return new FakeFuture(() -> {
            });
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task, java.time.Instant startTime, java.time.Duration period) {
            return schedule(task, startTime);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task, java.time.Duration period) {
            return schedule(task, java.time.Instant.now());
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task, java.time.Instant startTime, java.time.Duration delay) {
            return schedule(task, startTime);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task, java.time.Duration delay) {
            return schedule(task, java.time.Instant.now());
        }
    }

    record FakeFuture(Runnable onCancel) implements java.util.concurrent.ScheduledFuture<Object> {
        @Override public long getDelay(java.util.concurrent.TimeUnit unit) { return 0; }
        @Override public int compareTo(java.util.concurrent.Delayed o) { return 0; }
        @Override public boolean cancel(boolean mayInterrupt) { onCancel.run(); return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return false; }
        @Override public Object get() { return null; }
        @Override public Object get(long t, java.util.concurrent.TimeUnit u) { return null; }
    }
}
