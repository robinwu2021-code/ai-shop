package ai.neargo.shop.invbridge;

import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 对差任务的**结论可信性**。
 *
 * <p>这个任务的全部价值在于「它说零，就真的是零」—— 它攒的是切换库存真相源的
 * 唯一判据。所以这里测的不是「能不能跑」，是<b>三种结局有没有被如实报出来</b>：
 * 扫完且干净、扫完但有差异、<b>没扫完</b>。第三种最危险：那时候报「成功」，
 * 等于把「看过的那些没问题」当成「没问题」。
 */
class InventoryReconJobTest {

    /** 只需要 diffOnly，其余方法用不到 —— 手写替身而不是 mock 整个接口，读起来一眼看清返回什么。 */
    private static InventoryReconJob jobReturning(InventoryBackfillService.Report report) {
        InventoryBackfillService svc = new InventoryBackfillService() {
            @Override
            public Report diffOnly(int maxScan) {
                return report;
            }

            @Override
            public Report run(boolean dryRun, int limit, Long afterId) {
                throw new AssertionError("对差任务不该走写路径 —— 它只准调 diffOnly");
            }

            @Override
            public int migrateHeldLocks(int limit) {
                throw new AssertionError("对差任务不该走写路径 —— 它只准调 diffOnly");
            }
        };
        return new InventoryReconJob(svc, mock(JobSupport.class));
    }

    @Test
    @DisplayName("★★★ 没扫完必须判失败 —— 那时候报「成功」等于把抽样当全量")
    void incompleteScanFailsInsteadOfReportingSuccess() {
        /*
         * diffOnly 翻不完时把 clean 强制为 false 并留下 nextAfterId。
         * 如果这里只看 diffs.isEmpty()，就会得到一行绿色的「成功，差异 0」——
         * 而它真正的含义是「已扫的那 200 个里没差异，剩下的没看」。
         * 一道只抽样的闸门比没有闸门更坏：它给了一个读的人会误读的结论。
         */
        InventoryReconJob job = jobReturning(new InventoryBackfillService.Report(
                200, 0, 0, 0, false, 12345L, List.of()));

        JobResult r = job.run(null);

        assertThat(r.status()).as("没扫完不能算成功").isEqualTo(ai.neargo.job.api.JobStatus.FAILED);
        assertThat(r.detail()).contains("未扫完").contains("200");
    }

    @Test
    @DisplayName("★★ 有差异要报失败，并把差异与待搬分开说")
    void diffsAreReportedAsFailureWithBothCounts() {
        /*
         * clean=false 的两种成因对运营是两件事：
         *   差异 = 两边数不一样；待搬 = 进销存里根本还没这个物料。
         * 后者不会出现在 diffs 里（见 Report 构造器的注释），只报 diffs.size()
         * 会漏掉一整类，而那一类恰恰是「切过去就查无此货」。
         */
        InventoryReconJob job = jobReturning(new InventoryBackfillService.Report(
                209, 0, 0, 3, null, List.of(
                        new InventoryBackfillService.Diff("M0001", "ST0001", "SK0001", 10, 8, 0, 0))));

        JobResult r = job.run(null);

        assertThat(r.status()).isEqualTo(ai.neargo.job.api.JobStatus.FAILED);
        assertThat(r.detail()).contains("差异 1").contains("待搬 3");
    }

    @Test
    @DisplayName("扫完且为零才算成功 —— 这一行才是 D2 的判据来源")
    void cleanScanSucceeds() {
        InventoryReconJob job = jobReturning(new InventoryBackfillService.Report(
                209, 0, 209, 0, null, List.of()));

        JobResult r = job.run(null);

        assertThat(r.status()).isEqualTo(ai.neargo.job.api.JobStatus.SUCCESS);
        assertThat(r.detail()).contains("209").contains("差异 0");
    }
}
