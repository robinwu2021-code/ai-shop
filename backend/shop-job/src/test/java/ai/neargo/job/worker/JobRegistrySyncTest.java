package ai.neargo.job.worker;

import ai.neargo.job.api.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 注册表对齐 + 声明同步。**对齐必须是幂等的全量对齐，不是打补丁。** */
class JobRegistrySyncTest {

    private WorkerTestFixture f;
    private WorkerTestFixture.ManualScheduler scheduler;
    private WorkerTestFixture.FakeBusiness biz;
    private JobRegistry registry;
    private JobSyncService sync;

    @BeforeEach
    void setUp() {
        f = new WorkerTestFixture();
        scheduler = new WorkerTestFixture.ManualScheduler();
        biz = new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.SUCCESS, "好", null, 200));
        registry = new JobRegistry(scheduler, f.definitions, f.runs,
                new JobRunner(biz, f.runs, f.logs, f.props));
        sync = new JobSyncService(biz, f.definitions, registry, f.props);
    }

    @Test
    @DisplayName("首次同步：从业务系统取回声明，写进库，排上调度")
    void firstSyncRegistersEverything() {
        biz.declarations = List.of(
                WorkerTestFixture.decl("a", "0 0 3 * * *"),
                WorkerTestFixture.decl("b", "0 */10 * * * *"));

        sync.syncOnce();

        assertEquals(Set.of("a", "b"), registry.scheduledNames());
        assertEquals("中文名-a", f.definitions.findByName("a").displayName(),
                "运营页面要显示中文名，不是 handler 名");
        assertNotNull(f.runs.findByName("a"), "排上就该写 next_run_at，省得运营人肉解 cron");
    }

    @Test
    @DisplayName("同步是幂等的：同样的声明再来一遍，不重复排程")
    void syncIsIdempotent() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();
        int afterFirst = scheduler.tasks.size();

        sync.syncOnce();
        sync.syncOnce();

        assertEquals(afterFirst, scheduler.tasks.size(), "重复排程会让一个任务一轮跑好几遍");
    }

    @Test
    @DisplayName("运营改了 cron：取消旧的、排上新的")
    void cronChangeReschedules() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();

        f.definitions.updateCron("a", "0 40 4 * * *", "ops:zhang");
        JobRegistry.SyncReport r = registry.sync();

        assertEquals(1, r.rescheduled());
        assertEquals(1, scheduler.tasks.size(), "旧的没取消的话，两条排程会同时在跑");
    }

    @Test
    @DisplayName("运营关掉任务：取消调度，而不是让它空跑一趟")
    void disableCancelsScheduling() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();

        f.definitions.setEnabled("a", false, "ops:zhang");
        JobRegistry.SyncReport r = registry.sync();

        assertEquals(1, r.removed());
        assertTrue(registry.scheduledNames().isEmpty());
        assertTrue(scheduler.tasks.isEmpty(),
                "空跑的实现会在日志里留一堆「跳过」，而运营看到的是「它还在跑」");
    }

    @Test
    @DisplayName("非法 cron 只跳过它自己，不能把整轮同步带崩")
    void invalidCronDoesNotBreakTheWholeSync() {
        biz.declarations = List.of(
                WorkerTestFixture.decl("good", "0 0 3 * * *"),
                WorkerTestFixture.decl("bad", "0 0 3 * * *"));
        sync.syncOnce();
        f.definitions.updateCron("bad", "这不是 cron", "ops:zhang");

        JobRegistry.SyncReport r = registry.sync();

        assertEquals(1, r.invalid());
        assertTrue(registry.scheduledNames().contains("good"),
                "一个任务的配置错了，不该让其它任务一起停");
    }

    @Test
    @DisplayName("★★ 取不到声明时保持现状 —— 绝不能理解成「代码里都没了」")
    void unreachableBusinessMustNotDisableEverything() {
        biz.declarations = List.of(
                WorkerTestFixture.decl("a", "0 0 3 * * *"),
                WorkerTestFixture.decl("b", "0 0 4 * * *"));
        sync.syncOnce();
        assertEquals(2, registry.scheduledNames().size());

        // 业务系统正在发布，问不到
        biz.fetchFailure = new IllegalStateException("Connection refused");
        sync.syncOnce();

        assertFalse(f.definitions.findByName("a").missing(),
                "一次网络抖动换来全线停摆 —— 这是本模块最危险的一条路径");
        assertEquals(2, registry.scheduledNames().size(), "任务应当照常跑");
    }

    @Test
    @DisplayName("★ 声明返回空清单，同样不按「代码里都没了」处理")
    void emptyDeclarationsMustNotDisableEverything() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();

        biz.declarations = List.of();
        sync.syncOnce();

        assertFalse(f.definitions.findByName("a").missing());
        assertEquals(Set.of("a"), registry.scheduledNames(),
                "真要下线全部任务，运营在页面上关就是了 —— 那是有人做的决定，看得见");
    }

    @Test
    @DisplayName("代码里删掉一个任务：标 missing 并停止调度，但不删行")
    void removedFromCodeIsMarkedAndUnscheduled() {
        biz.declarations = List.of(
                WorkerTestFixture.decl("a", "0 0 3 * * *"),
                WorkerTestFixture.decl("b", "0 0 4 * * *"));
        sync.syncOnce();

        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();

        assertTrue(f.definitions.findByName("b").missing());
        assertEquals(Set.of("a"), registry.scheduledNames());
        assertNotNull(f.definitions.findByName("b"), "静默消失比留着危险：运营会以为它还在跑");
    }

    @Test
    @DisplayName("发版不覆盖运营改过的 cron（端到端再验一次，DAO 层之外）")
    void redeployKeepsOperatorsCron() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();
        f.definitions.updateCron("a", "0 40 4 * * *", "ops:zhang");

        sync.syncOnce();   // 业务系统重新上线，声明里 cron 仍是 3 点

        assertEquals("0 40 4 * * *", f.definitions.findByName("a").cron(),
                "运营改的 cron 被发版冲掉了 —— 没有报错没有日志，"
                + "只会有人某天问「为什么它又变回三点了」");
    }
}
