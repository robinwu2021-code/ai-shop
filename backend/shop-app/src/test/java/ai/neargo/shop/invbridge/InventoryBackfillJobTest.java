package ai.neargo.shop.invbridge;

import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 生产的搬运任务<b>真的会搬在途预留</b>。
 *
 * <p>{@code migrateHeldLocks} 写好了、测试里也调了，但任务从来不调它：线上只搬余额，
 * 平台 held&gt;0 而进销存 reserved=0，只要还有未付款的单对差就永远不干净 ——
 * 硬切过去的话，那些已被下单占住的货会重新可售。
 */
class InventoryBackfillJobTest {

    private final List<String> calls = new ArrayList<>();

    private InventoryBackfillJob job(boolean dryRun) {
        InventoryBackfillService svc = new InventoryBackfillService() {
            @Override
            public Report run(boolean dry, int limit, Long afterId) {
                calls.add("run(dry=" + dry + ")");
                return new Report(1, 0, 1, 0, true, null, List.of());
            }

            @Override
            public int migrateHeldLocks(int limit) {
                calls.add("migrateHeldLocks");
                return 2;
            }

            @Override
            public Report diffOnly(int maxScan) {
                throw new AssertionError("搬运任务不走只读对差");
            }
        };
        InventoryBackfillJob job = new InventoryBackfillJob(svc, mock(JobSupport.class));
        ReflectionTestUtils.setField(job, "dryRun", dryRun);
        ReflectionTestUtils.setField(job, "batch", 500);
        return job;
    }

    @Test
    @DisplayName("★★★ 真搬的那一轮：先搬在途预留，再搬余额并对差；报告里看得见搬了几笔")
    void writeRunMigratesHeldLocksFirst() {
        JobResult r = job(false).run(null);

        assertThat(calls).containsExactly("migrateHeldLocks", "run(dry=false)");
        assertThat(r.detail()).contains("held=2");
    }

    @Test
    @DisplayName("★★ dry-run 只算不写：不搬在途预留")
    void dryRunDoesNotMigrateHeldLocks() {
        JobResult r = job(true).run(null);

        assertThat(calls).containsExactly("run(dry=true)");
        assertThat(r.detail()).contains("held=0").contains("(dry-run)");
    }
}
