package ai.neargo.shop.job;

import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自检任务。**它的全部价值在那行 detail 上** —— 回显不全，它就不是探针，
 * 只是一个什么都不做的任务。
 */
class SelfTestJobTest {

    private final SelfTestJob job = new SelfTestJob();

    @Test
    @DisplayName("回显调用的四要素：runId、触发方式、业务日期、参数")
    void echoesEverythingItReceived() {
        JobResult r = job.run(new JobInvocation("run-abc", TriggerType.MANUAL,
                LocalDate.of(2026, 8, 27), Map.of("k", "v")));

        assertThat(r.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(r.detail())
                .contains("run-abc")
                .contains("MANUAL")
                .contains("2026-08-27")
                .contains("k=v");
    }

    @Test
    @DisplayName("参数为空时明说「未配置」—— 不能再说「JobRunner 尚未下传」，那条已经修了")
    void emptyParamsSaysWhy() {
        JobResult r = job.run(new JobInvocation("r", TriggerType.CRON, null, Map.of()));
        // 措辞不是小事：生产日志里写着「尚未下传」，而下传早就接上了 ——
        // 下一个看到它的人会去查一个不存在的缺陷
        assertThat(r.detail()).contains("（未配置）");
        assertThat(r.detail()).doesNotContain("尚未下传");
        assertThat(r.detail()).contains("(未传)");
    }

    @Test
    @DisplayName("参数按键排序：两次自检的输出能直接比对")
    void paramsAreSorted() {
        Map<String, String> unordered = new LinkedHashMap<>();
        unordered.put("z", "1");
        unordered.put("a", "2");
        JobResult r = job.run(new JobInvocation("r", TriggerType.CRON, null, unordered));
        assertThat(r.detail()).contains("{a=2, z=1}");
    }

    @Test
    @DisplayName("detail 不能超过 job_run.detail 的 VARCHAR(500)，超了要看得出是被截的")
    void detailFitsTheColumn() {
        Map<String, String> big = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            big.put("key" + i, "value".repeat(10));
        }
        JobResult r = job.run(new JobInvocation("r", TriggerType.CRON, null, big));

        assertThat(r.detail()).hasSizeLessThanOrEqualTo(500);
        assertThat(r.detail()).endsWith("...");
    }

    @Test
    @DisplayName("声明与实现同名 —— 对不上会让整个业务系统起不来")
    void declarationMatchesName() {
        assertThat(job.jobselftestDeclaration().handlerName()).isEqualTo(job.name());
        assertThat(job.jobselftestDeclaration().manualTrigger()).isTrue();
        assertThat(job.jobselftestDeclaration().logEveryRun())
                .as("稀疏日志会把「一直成功」的自检记录省掉，而那正是要看的东西")
                .isTrue();
    }
}
