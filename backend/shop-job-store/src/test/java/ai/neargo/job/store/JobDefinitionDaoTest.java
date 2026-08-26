package ai.neargo.job.store;

import ai.neargo.job.api.JobDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JobDefinitionDao} 的行为。**重点是 upsert 那条规则**——
 * 它被违反时不会有任何声音，只会有人某天问「为什么它又变回三点了」。
 */
class JobDefinitionDaoTest {

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
                "0 25 3 * * *", true, 60, 1800, true, true);
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
        assertEquals(2, dao.findSchedulable().size());

        // 下一版代码里只剩 recon-scan
        int marked = dao.markMissingExcept(List.of("recon-scan"));

        assertEquals(1, marked);
        assertNotNull(dao.findByName("plan-expiry"), "静默消失比留着危险：运营会以为它还在跑");
        assertTrue(dao.findByName("plan-expiry").missing());
        assertEquals(List.of("recon-scan"),
                dao.findSchedulable().stream().map(JobDefinitionRow::jobName).toList(),
                "标了 missing 就不该再被调度");
    }

    @Test
    @DisplayName("任务重新出现时，missing 要清掉")
    void reappearingJobClearsMissing() {
        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");
        dao.markMissingExcept(List.of());
        assertTrue(dao.findByName("plan-expiry").missing());

        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");
        assertFalse(dao.findByName("plan-expiry").missing(), "任务回来了，标记要清掉");
    }

    @Test
    @DisplayName("关掉的任务不进调度集合")
    void disabledJobsAreNotSchedulable() {
        dao.upsertFromCode("plan-expiry", DECL, "PLATFORM");
        dao.setEnabled("plan-expiry", false, "ops:li");
        assertTrue(dao.findSchedulable().isEmpty());
    }
}
