package ai.neargo.job.worker;

import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import ai.neargo.job.store.JobDefinitionRow;
import ai.neargo.job.store.JobRunRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link JobRunner} 的状态机。**这里的每一条错了都会让告警失真。** */
class JobRunnerTest {

    private WorkerTestFixture f;
    private JobDefinitionRow def;

    @BeforeEach
    void setUp() {
        f = new WorkerTestFixture();
        f.definitions.upsertFromCode("t", WorkerTestFixture.decl("t", "0 0 3 * * *"), "PLATFORM");
        def = f.definitions.findByName("t");
    }

    private JobRunner runnerWith(WorkerTestFixture.FakeBusiness biz) {
        return new JobRunner(biz, f.runs, f.logs, f.props);
    }

    @Test
    @DisplayName("成功一轮：job_run 记下结果，连续失败清零，run_count 加一")
    void successIsRecorded() {
        var biz = new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.SUCCESS, "关闭 12 单", null, 200));
        runnerWith(biz).run(def, TriggerType.CRON);

        JobRunRow row = f.runs.findByName("t");
        assertEquals("SUCCESS", row.lastStatus());
        assertEquals("关闭 12 单", row.detail());
        assertEquals(0, row.consecutiveFailures());
        assertEquals(1, row.runCount());
        assertFalse(row.running(), "跑完要把 running 放掉，否则页面永远显示「正在跑」");
    }

    @Test
    @DisplayName("★ 只有 FAILED 累加连续失败；SKIPPED / TIMEOUT / UNREACHABLE 都不动它")
    void onlyFailedIncrementsConsecutiveFailures() {
        var biz = new WorkerTestFixture.FakeBusiness().returning(
                InvokeOutcome.of(JobStatus.FAILED, "d", "e", 200));
        runnerWith(biz).run(def, TriggerType.CRON);
        assertEquals(1, f.runs.findByName("t").consecutiveFailures());

        for (InvokeOutcome notAFailure : List.of(
                InvokeOutcome.of(JobStatus.SKIPPED, "跳过", null, 409),
                InvokeOutcome.timeout(60))) {
            runnerWith(new WorkerTestFixture.FakeBusiness().returning(notAFailure))
                    .run(def, TriggerType.CRON);
            assertEquals(1, f.runs.findByName("t").consecutiveFailures(),
                    notAFailure.status() + " 被算成了故障 —— 告警会在一切正常时响，"
                    + "而那样的告警等于没有告警");
        }

        runnerWith(new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.SUCCESS, "好了", null, 200)))
                .run(def, TriggerType.CRON);
        assertEquals(0, f.runs.findByName("t").consecutiveFailures(), "成功要清零");
    }

    @Test
    @DisplayName("★★ 只有 UNREACHABLE 才重试 —— TIMEOUT 重试等于让不幂等的任务跑两遍")
    void retriesOnlyWhenNothingCouldHaveRun() {
        var unreachable = new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.unreachable("ConnectException"));
        runnerWith(unreachable).run(def, TriggerType.CRON);
        assertEquals(4, unreachable.received.size(),
                "连接没建立 = 业务侧什么都没跑，应当重试满 3 次（首次 + 3）");
        assertEquals(TriggerType.CRON, unreachable.received.get(0).type());
        assertEquals(TriggerType.RETRY, unreachable.received.get(1).type(), "重试要标成 RETRY");

        var timeout = new WorkerTestFixture.FakeBusiness().returning(InvokeOutcome.timeout(60));
        runnerWith(timeout).run(def, TriggerType.CRON);
        assertEquals(1, timeout.received.size(),
                "超时说明业务侧多半正在跑，重试会让日结/关单这类不幂等的事跑两遍");

        var failed = new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.FAILED, "d", "e", 200));
        runnerWith(failed).run(def, TriggerType.CRON);
        assertEquals(1, failed.received.size(), "跑了但失败了，重不重试是业务的决定");

        var skipped = new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.SKIPPED, "跳过", null, 409));
        runnerWith(skipped).run(def, TriggerType.CRON);
        assertEquals(1, skipped.received.size(), "锁没抢到，重试只会再被拒一次");
    }

    @Test
    @DisplayName("重试成功后，结果是成功而不是失败")
    void retryThatSucceedsIsASuccess() {
        var biz = new WorkerTestFixture.FakeBusiness().returning(
                InvokeOutcome.unreachable("ConnectException"),
                InvokeOutcome.of(JobStatus.SUCCESS, "第二次通了", null, 200));
        runnerWith(biz).run(def, TriggerType.CRON);

        assertEquals("SUCCESS", f.runs.findByName("t").lastStatus());
        assertEquals(2, biz.received.size());
    }

    @Test
    @DisplayName("log_every_run=1：每轮都落一行完整日志，run_id 两边对得上")
    void everyRunIsLogged() {
        var biz = new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.SUCCESS, "好", null, 200));
        runnerWith(biz).run(def, TriggerType.CRON);
        runnerWith(biz).run(def, TriggerType.CRON);

        var rows = f.logs.findByJob("t", 10, 0);
        assertEquals(2, rows.size());
        assertEquals("SUCCESS", rows.get(0).status());
        assertNotNull(rows.get(0).finishedAt(), "落了 RUNNING 行就要补完，否则永远挂着");
        assertEquals("test-worker", rows.get(0).workerInstance());
    }

    @Test
    @DisplayName("★ log_every_run=0：一直成功就一行不写；变红写一行，恢复再写一行")
    void sparseLoggingRecordsOnlyTransitions() {
        f.definitions.setEnabled("t", true, "test");
        f.jdbc.sql("UPDATE job_definition SET log_every_run = 0 WHERE job_name = 't'").update();
        JobDefinitionRow sparse = f.definitions.findByName("t");

        var ok = InvokeOutcome.of(JobStatus.SUCCESS, "好", null, 200);
        runnerWith(new WorkerTestFixture.FakeBusiness().returning(ok)).run(sparse, TriggerType.CRON);
        assertEquals(1, f.logs.findByJob("t", 10, 0).size(),
                "第一次成功要留一行 —— 没有它，日志里看不出这个任务跑过");

        runnerWith(new WorkerTestFixture.FakeBusiness().returning(ok)).run(sparse, TriggerType.CRON);
        runnerWith(new WorkerTestFixture.FakeBusiness().returning(ok)).run(sparse, TriggerType.CRON);
        assertEquals(1, f.logs.findByJob("t", 10, 0).size(),
                "一直成功不该继续写 —— 高频任务会把日志表撑成本库最大的那张");

        runnerWith(new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.FAILED, "炸了", "E", 200)))
                .run(sparse, TriggerType.CRON);
        assertEquals(2, f.logs.findByJob("t", 10, 0).size(), "变红必须留痕");

        runnerWith(new WorkerTestFixture.FakeBusiness().returning(ok)).run(sparse, TriggerType.CRON);
        assertEquals(3, f.logs.findByJob("t", 10, 0).size(), "恢复也是一次状态变化，同样要留痕");
    }

    @Test
    @DisplayName("bizDate 取昨天 —— 凌晨跑的日结算的是上一个自然日")
    void bizDateIsYesterday() {
        var biz = new WorkerTestFixture.FakeBusiness()
                .returning(InvokeOutcome.of(JobStatus.SUCCESS, "好", null, 200));
        runnerWith(biz).run(def, TriggerType.CRON);
        assertEquals(java.time.LocalDate.now().minusDays(1), biz.received.get(0).bizDate(),
                "给今天等于算了半天的账，而这种错不报错，只让数字对不上");
    }
}
