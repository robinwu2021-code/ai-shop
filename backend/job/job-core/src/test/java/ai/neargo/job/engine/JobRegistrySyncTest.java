package ai.neargo.job.engine;

import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
                new JobRunner(biz, f.runs, f.logs, f.props), f.props);
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
    @DisplayName("★★ startDisabled：首次入库全部为「停」——页面上看得见，但一个都不跑")
    void startDisabledRegistersButDoesNotSchedule() {
        f.props.setStartDisabled(true);
        biz.declarations = List.of(
                WorkerTestFixture.decl("a", "0 0 3 * * *"),
                WorkerTestFixture.decl("b", "0 0 4 * * *"));

        sync.syncOnce();

        assertEquals(2, f.definitions.findAll().size(),
                "任务要登记进表 —— 运营得先看得见才谈得上打开");
        assertTrue(registry.scheduledNames().isEmpty(),
                "一次性放开 14 个任务是 14 处同时的行为变化，真出事时分不清是哪一个");
    }

    @Test
    @DisplayName("★ startDisabled 只管首次：运营开过之后，发版不能把它关回去")
    void startDisabledOnlyAffectsTheFirstInsert() {
        f.props.setStartDisabled(true);
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();

        f.definitions.setEnabled("a", true, "ops:zhang");   // 运营在页面上打开
        sync.syncOnce();                                    // 下一次发版

        assertTrue(f.definitions.findByName("a").enabled(),
                "运营开好的任务被发版关回去了 —— 没有报错，只是它从某天起又不跑了");
        assertEquals(Set.of("a"), registry.scheduledNames());
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

    // ── 手动触发 ────────────────────────────────────────────────
    //
    // **这一组的判据是「业务侧被调用了」，不是「请求落库了」。**
    // 之前只有落库那半边的断言，于是从运营端到执行之间整条链路断着 ——
    // 按钮返回 200、库里记着请求、任务永远不跑 —— 而测试一直是绿的。

    @Test
    @DisplayName("★ 运营点「立即执行」：下一轮轮询真的把它发到业务系统，而不是只在库里记一笔")
    void manualTriggerActuallyReachesBusiness() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();
        int before = biz.received.size();

        assertTrue(f.definitions.requestTrigger("a", LocalDateTime.now(), "ops:zhang"));
        sync.syncOnce();
        // triggerNow 是异步排一次：跑一个慢任务不该堵住整轮轮询
        Runnable queued = scheduler.lastImmediate.get();
        assertNotNull(queued, "手动触发应当排进调度器，而不是在轮询线程里同步跑");
        queued.run();

        assertEquals(before + 1, biz.received.size(), "业务系统必须真的收到这一次调用");
        assertEquals(TriggerType.MANUAL, biz.received.getLast().type());
        assertEquals(1, f.runs.findByName("a").runCount());
    }

    @Test
    @DisplayName("★ 只跑一次：水位推上去之后，后面每一轮都不该再捞到它")
    void manualTriggerRunsExactlyOnce() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();
        f.definitions.requestTrigger("a", LocalDateTime.now(), "ops:zhang");

        sync.syncOnce();
        scheduler.lastImmediate.getAndSet(null).run();
        int after = biz.received.size();

        sync.syncOnce();
        sync.syncOnce();

        assertNull(scheduler.lastImmediate.get(), "第二、三轮不该再排一次");
        assertEquals(after, biz.received.size(),
                "清标志式的实现会在这里每轮跑一次，直到有人发现");
    }

    @Test
    @DisplayName("水位取请求时刻本身，不取 now —— 否则读出行之后新来的请求会被吞掉")
    void watermarkIsTheRequestInstantNotNow() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();
        // **拉开五分钟**：写成 now() 的话，请求时刻与 markTriggered 里的 now()
        // 落在同一秒，H2 的秒精度把两者磨平 —— 断言会通过，而变异（改用 now）
        // 照样通过。那种只在跨秒时才红的闸门，等于没有闸门
        LocalDateTime requestedAt = LocalDateTime.now().withNano(0).minusMinutes(5);
        f.definitions.requestTrigger("a", requestedAt, "ops:zhang");

        sync.syncOnce();

        assertEquals(requestedAt, f.definitions.findByName("a").lastTriggeredAt(),
                "取 now 的话，这两个时刻之间新来的那次请求就没了");
    }

    @Test
    @DisplayName("停用的任务不受理手动触发 —— 运营端已经用 403 挡了，这里是第二道")
    void disabledJobIsNotTriggered() {
        biz.declarations = List.of(WorkerTestFixture.decl("a", "0 0 3 * * *"));
        sync.syncOnce();
        f.definitions.requestTrigger("a", LocalDateTime.now(), "ops:zhang");
        f.definitions.setEnabled("a", false, "ops:zhang");
        scheduler.lastImmediate.set(null);

        sync.syncOnce();

        assertNull(scheduler.lastImmediate.get(), "库被手工改过时，worker 也不能跑一个已停的任务");
    }
}
