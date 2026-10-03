package ai.neargo.job.store;

import ai.neargo.job.api.JobDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JobDefinitionDao} 的行为。**重点是 upsert 那条规则**——
 * 它被违反时不会有任何声音，只会有人某天问「为什么它又变回三点了」。
 */
class JobDefinitionDaoTest {

    /** 这些用例只有一个 target。**按 target 过滤是 2026-08-28 补的** —— 见 DAO 上的注释。 */
    private static final java.util.Set<String> PLATFORM = java.util.Set.of("PLATFORM");

    private JdbcClient jdbc;
    private JobDefinitionDao dao;

    private static final JobDeclaration DECL = JobDeclaration.daily(
            "plan-expiry", "增值包到期扫描", "扫出已过期的商家增值包并收回权益",
            "shop-merchant", "0 25 3 * * *");

    @BeforeEach
    void setUp() {
        jdbc = JobStoreTestSupport.freshDatabase();
        dao = new JobDefinitionDao(jdbc);
    }

    @Test
    @DisplayName("首次启动：按代码的默认值建行")
    void firstBootInsertsWithCodeDefaults() {
        assertTrue(dao.upsertFromCode("plan-expiry", DECL, "PLATFORM"), "首次应报「新建」");

        JobDefinitionRow row = dao.findByName("plan-expiry");
        assertNotNull(row);
        assertEquals("0 25 3 * * *", row.cron());
        assertTrue(row.enabled());
        assertEquals("增值包到期扫描", row.displayName());
        assertEquals("CODE", row.source());
        assertFalse(row.missing());
    }

    @Test
    @DisplayName("★ 运营改过的 cron，不能被下一次发版冲掉")
    void redeployMustNotOverwriteOperatorsCron() {
        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");

        // 运营在页面上把它改到凌晨 4:40，并关掉
        dao.updateCron("plan-expiry", "0 40 4 * * *", "ops:zhang");
        dao.setEnabled("plan-expiry", false, "ops:zhang");

        // 发版：同一份代码声明再 upsert 一次（代码里 cron 仍是 3:25、enabled 仍是 true）
        assertFalse(dao.upsertFromCode("plan-expiry", DECL, "PLATFORM"), "第二次不应报「新建」");

        JobDefinitionRow row = dao.findByName("plan-expiry");
        assertEquals("0 40 4 * * *", row.cron(),
                "运营改的 cron 被代码冲掉了 —— 这种缺陷没有报错、没有日志，"
                + "只会有人某天问「为什么它又变回三点了」");
        assertFalse(row.enabled(), "运营关掉的任务被发版重新打开了");
        assertEquals("ops:zhang", row.updatedBy(), "「谁改的」不能丢");
    }

    @Test
    @DisplayName("代码才知道的那几列，发版时要更新成最新的")
    void redeployRefreshesCodeOwnedColumns() {
        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");

        JobDeclaration renamed = new JobDeclaration(
                "plan-expiry", "套餐到期扫描（改名后）", "新的说明", "shop-settle",
                "0 25 3 * * *", true, 600, 900, true, true);
        dao.upsertFromCode("plan-expiry", renamed, "PLATFORM");

        JobDefinitionRow row = dao.findByName("plan-expiry");
        assertEquals("套餐到期扫描（改名后）", row.displayName());
        assertEquals("新的说明", row.description());
        assertEquals("shop-settle", row.ownerModule());
    }

    @Test
    @DisplayName("运营自己建的任务（source=MANUAL），代码一个字都不许碰")
    void manualJobsAreNeverTouchedByCode() {
        jdbc.sql("""
                INSERT INTO job_definition
                    (job_name, display_name, handler_name, target, cron, source, owner_module)
                VALUES ('recon-scan-alipay', '对账自查·支付宝', 'recon-scan', 'PLATFORM',
                        '0 5 3 * * *', 'MANUAL', 'shop-settle')
                """).update();

        JobDeclaration handlerDecl = JobDeclaration.daily(
                "recon-scan", "对账自查", "扫出两边对不上的流水", "shop-settle", "0 */10 * * * *");
        dao.upsertFromCode("recon-scan", handlerDecl, "PLATFORM");

        JobDefinitionRow manual = dao.findByName("recon-scan-alipay");
        assertEquals("对账自查·支付宝", manual.displayName(), "MANUAL 行被代码改掉了");
        assertEquals("0 5 3 * * *", manual.cron());
        assertNotNull(dao.findByName("recon-scan"), "代码声明本身应另起一行");
        assertEquals(2, dao.findAll().size());
    }

    @Test
    @DisplayName("代码里删掉的任务：标 missing 而不是删行，且不再被调度")
    void removedJobsAreMarkedNotDeleted() {
        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");
        dao.upsertFromCode("recon-scan", JobDeclaration.daily(
                "recon-scan", "对账自查", "d", "shop-settle", "0 */10 * * * *"), "PLATFORM");
        assertEquals(2, dao.findSchedulable(PLATFORM).size());

        // 下一版代码里只剩 recon-scan
        int marked = dao.markMissingExcept(List.of("recon-scan"), PLATFORM);

        assertEquals(1, marked);
        assertNotNull(dao.findByName("plan-expiry"), "静默消失比留着危险：运营会以为它还在跑");
        assertTrue(dao.findByName("plan-expiry").missing());
        assertEquals(List.of("recon-scan"),
                dao.findSchedulable(PLATFORM).stream().map(JobDefinitionRow::jobName).toList(),
                "标了 missing 就不该再被调度");
    }

    @Test
    @DisplayName("任务重新出现时，missing 要清掉")
    void reappearingJobClearsMissing() {
        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");
        dao.markMissingExcept(List.of(), PLATFORM);
        assertTrue(dao.findByName("plan-expiry").missing());

        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");
        assertFalse(dao.findByName("plan-expiry").missing(), "任务回来了，标记要清掉");
    }

    @Test
    @DisplayName("关掉的任务不进调度集合")
    void disabledJobsAreNotSchedulable() {
        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");
        dao.setEnabled("plan-expiry", false, "ops:li");
        assertTrue(dao.findSchedulable(PLATFORM).isEmpty());
    }

    @Test
    @DisplayName("★★★ 改了代码里的超时/持锁/日志开关，下一轮轮询要落到已存在的行上")
    void codeOwnedNumbersAreUpdatedOnExistingRows() {
        dao.upsertFromCode("j", new JobDeclaration("j", "名", "说明", "mod",
                "0 0 3 * * *", true, 60, 180, true, true), "PLATFORM");

        // 运营改了 cron —— 这一列归运营，下面的更新不能碰它
        dao.updateCron("j", "0 30 4 * * *", "ops:zhang");

        // 代码里把数字改了、日志关了，再轮询一轮
        dao.upsertFromCode("j", new JobDeclaration("j", "名", "说明", "mod",
                "0 0 3 * * *", true, 480, 540, true, false), "PLATFORM");

        JobDefinitionRow r = dao.findByName("j");
        /*
         * 这四列此前**只在首次 INSERT 时写一次**：代码改了不生效（不在 UPDATE 里），
         * 运营端也没有改它们的入口。生产上 11 个任务的 timeout_sec 全冻在 60，
         * 而没有任何地方会说改代码没用。
         */
        assertEquals(480, r.timeoutSec(), "改了代码却没生效 —— 那正是修复前的形态");
        assertEquals(540, r.lockAtMostSec());
        assertFalse(r.logEveryRun(), "高频任务关掉全量日志，靠的就是这条更新");
        // 而运营那半边不能被碰
        assertEquals("0 30 4 * * *", r.cron(), "cron 归运营，代码不许覆盖");
    }

    @Test
    @DisplayName("★★★ 两个 target 不同的 worker 不能互相把对方的任务标成「代码里已不存在」")
    void workersOfDifferentTargetsDoNotFightOverMissing() {
        JobDeclaration a = new JobDeclaration("only-in-platform", "甲", "d", "m",
                "0 0 3 * * *", true, 600, 900, true, true);
        JobDeclaration b = new JobDeclaration("only-in-local", "乙", "d", "m",
                "0 0 4 * * *", true, 600, 900, true, true);
        dao.upsertFromCode("only-in-platform", a, "PLATFORM");
        dao.upsertFromCode("only-in-local", b, "LOCAL");

        /*
         * PLATFORM 那个 worker 轮询：它只认识自己那份声明。
         * 不按 target 限定的话，它会把 LOCAL 的任务也标成 missing ——
         * 而 LOCAL 那个 worker 下一轮又标回来。2026-08-28 生产上就是这样，
         * 两边每 30 秒互标一次，日志里同一条 WARN 无限重复。
         */
        int marked = dao.markMissingExcept(List.of("only-in-platform"),
                java.util.Set.of("PLATFORM"));

        assertEquals(0, marked, "不该动别人 target 下的任何一行");
        assertFalse(dao.findByName("only-in-local").missing(),
                "LOCAL 的任务被 PLATFORM 的 worker 标成失联了 —— 那是修复前的形态");
        assertFalse(dao.findByName("only-in-platform").missing());
    }

    @Test
    @DisplayName("★★ 只排自己 target 下的任务 —— 排了别人的，每轮都会 UNREACHABLE")
    void schedulableIsScopedToOwnTargets() {
        dao.upsertFromCode("p1", new JobDeclaration("p1", "甲", "d", "m",
                "0 0 3 * * *", true, 600, 900, true, true), "PLATFORM");
        dao.upsertFromCode("l1", new JobDeclaration("l1", "乙", "d", "m",
                "0 0 4 * * *", true, 600, 900, true, true), "LOCAL");

        assertThat(dao.findSchedulable(java.util.Set.of("PLATFORM")))
                .extracting(JobDefinitionRow::jobName)
                .containsExactly("p1");
        assertThat(dao.findSchedulable(java.util.Set.of("LOCAL")))
                .extracting(JobDefinitionRow::jobName)
                .containsExactly("l1");
        assertThat(dao.findSchedulable(java.util.Set.of()))
                .as("一个 target 都没有的 worker 什么都不该排")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 手动触发只被自己 target 的 worker 领走 —— 领错了这次触发就被吃掉")
    void triggerRequestIsScopedToOwnTargets() {
        dao.upsertFromCode("l1", new JobDeclaration("l1", "乙", "d", "m",
                "0 0 4 * * *", true, 600, 900, true, true), "LOCAL");
        assertTrue(dao.requestTrigger("l1", java.time.LocalDateTime.now(), "ops:zhang"));

        assertThat(dao.findTriggerRequested(java.util.Set.of("PLATFORM")))
                .as("PLATFORM 的 worker 领走 LOCAL 的请求 → 它跑不了，"
                        + "但水位已推高，页面上「已排队」消失而任务根本没跑")
                .isEmpty();
        assertThat(dao.findTriggerRequested(java.util.Set.of("LOCAL")))
                .extracting(JobDefinitionRow::jobName).containsExactly("l1");
    }

    @Test
    @DisplayName("★★★ target 首次插入时认领，之后别的 worker 轮询不许改它")
    void targetIsClaimedOnceNotRewrittenEveryPoll() {
        JobDeclaration d = new JobDeclaration("shared", "共享的", "d", "m",
                "0 0 3 * * *", true, 600, 900, true, true);
        // 独立调度器先见到它
        assertTrue(dao.upsertFromCode("shared", d, "PLATFORM"));
        assertEquals("PLATFORM", dao.findByName("shared").target());

        /*
         * 另一个 worker（没配 targets，退到占位名 LOCAL）也声明了同一个 handler。
         * 修复前它会把 target 改成 LOCAL，而独立调度器下一轮又改回 PLATFORM ——
         * 2026-08-28 生产上 12 行就是这样每 30 秒来回翻的：翻到不属于自己那一侧时，
         * 任务既排不上、手动触发也会被错误的 worker 领走，而没有任何报错。
         */
        assertFalse(dao.upsertFromCode("shared", d, "LOCAL"), "已存在，应当走更新而不是插入");

        assertEquals("PLATFORM", dao.findByName("shared").target(),
                "第一个见到这个 handler 的 worker 认领它 —— 之后改 target 必须是一次有意的动作");
        // 而「只有代码知道」的那几列照常刷新
        assertEquals("共享的", dao.findByName("shared").displayName());
    }
}
