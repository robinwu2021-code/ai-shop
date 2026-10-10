package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.api.JobStatus;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.paybridge.SettleBatchJob;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账期推进任务（TDD-账期推进与放款记录 批 1 · AC4 · §2.1 C）。
 *
 * <p>Job 用构造器直接 new（dry-run / 起始日是构造参数），不从容器取 ——
 * 两个开关各测两面，靠容器只能测配置文件里那一面。
 * **不加 {@code @TestPropertySource}**：多一个 Spring context 会挤掉缓存里的，
 * 本仓库为此红过一次（TopicFlowTest）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SettleBatchJobFlowTest {

    private static final String ENTITY = "M-BATCH-JOB";
    private static final long DAY = 86400000L;
    private static final String ZONE = "Asia/Shanghai";

    @Autowired
    private SettleBatchService batchService;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private SettleMappers.SettleBatchMapper batchMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("DELETE FROM stl_bill WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM stl_settle_batch WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM ord_status_log WHERE sub_order_no LIKE 'SUB-BJOB-%'");
            return null;
        });
    }

    @Test
    @DisplayName("★★★ dry-run 只报数不写库 —— 上线第一轮运营要先看一眼会动多少单")
    void dryRunReportsButWritesNothing() {
        /*
         * **相对断言，不写死「候选 1」。** preview 数的是全库：别的测试类留下的
         * PENDING 单会让这个数在全量里变成 7、单独跑是 1 —— 单独绿全量红，本仓库踩过多次。
         */
        int before = batchService.preview(null).toMark();
        givenBill("dr", System.currentTimeMillis() - 30 * DAY);

        JobResult r = new SettleBatchJob(batchService, null, true, "", ZONE).run(null);

        assertThat(r.detail()).contains("dry-run").contains("定 T2 候选 " + (before + 1));
        assertThat(reload("STL-BJOB-dr").getSettleableAt()).as("dry-run 不许写库").isNull();
        assertThat(reload("STL-BJOB-dr").getBatchNo()).isNull();
    }

    @Test
    @DisplayName("★★★ 不 dry-run：一轮四步，30 天前的单直接走到可放款")
    void oneRoundPushesOldBillToReconciled() {
        givenBill("go", System.currentTimeMillis() - 30 * DAY);

        JobResult r = new SettleBatchJob(batchService, null, false, "", ZONE).run(null);

        assertThat(r.status()).isEqualTo(JobStatus.SUCCESS);
        StlBill b = reload("STL-BJOB-go");
        assertThat(b.getSettleableAt()).isNotNull();
        assertThat(b.getBatchNo()).isNotNull();
        StlSettleBatch batch = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getBatchNo, b.getBatchNo()).last("LIMIT 1")));
        assertThat(batch.getStatus())
                .as("定T2 → 入批 → 截批 → 自查，一轮走完该停在 RECONCILED")
                .isEqualTo(StlSettleBatch.RECONCILED);
        assertThat(r.detail()).contains("定T2 1").contains("入批 1").contains("截批 1").contains("自查 1");
    }

    @Test
    @DisplayName("★★★ 起始日在明天：今天之前成交的一张都不动")
    void startDateTomorrowMovesNothing() {
        givenBill("fence", System.currentTimeMillis() - 30 * DAY);
        String tomorrow = LocalDate.now().plusDays(1).toString();

        JobResult r = new SettleBatchJob(batchService, null, false, tomorrow, ZONE).run(null);

        assertThat(r.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(r.detail()).as("没动任何单时 detail 留空").isNull();
        assertThat(reload("STL-BJOB-fence").getSettleableAt()).isNull();
    }

    @Test
    @DisplayName("★★ 起始日写错直接抛 —— 回落成「不限」正是要防的那个方向")
    void badStartDateFailsLoudly() {
        assertThatThrownBy(() -> SettleBatchJob.parseStart("2026/10/09", ZONE))
                .isInstanceOf(RuntimeException.class);
        assertThat(SettleBatchJob.parseStart("", ZONE)).isNull();
        assertThat(SettleBatchJob.parseStart(" ", ZONE)).isNull();
    }

    @Test
    @DisplayName("★★★ 四步顺序不能换，且一步失败不拖垮其余三步")
    void stepOrderIsFixedAndFailuresAreIsolated() {
        List<String> calls = new ArrayList<>();
        SettleBatchService stub = new StubBatchService() {
            @Override public int markSettleable(Long from) { calls.add("mark"); return 2; }
            @Override public int collectIntoBatches(Long from) { calls.add("collect"); throw new IllegalStateException("boom"); }
            @Override public int closeDueBatches() { calls.add("close"); return 1; }
            @Override public int reconcileClosedBatches() { calls.add("reconcile"); return 1; }
        };

        JobResult r = new SettleBatchJob(stub, null, false, "", ZONE).run(null);

        // 顺序：定 T2 → 入批 → 截批 → 自查。换序会让当轮新定 T2 的单赶不上入批，推迟一小时
        assertThat(calls).containsExactly("mark", "collect", "close", "reconcile");
        // 入批炸了，截批与自查照跑 —— 它们之间只有顺序依赖，没有「前一步成功」的依赖
        assertThat(r.status()).isEqualTo(JobStatus.FAILED);
        assertThat(r.detail()).contains("入批 失败").contains("截批 1").contains("自查 1");
    }

    /** 接口桩：没覆盖的方法都不该被 Job 调到 */
    private abstract static class StubBatchService implements SettleBatchService {
        @Override public int markSettleable() { throw new UnsupportedOperationException(); }
        @Override public int collectIntoBatches() { throw new UnsupportedOperationException(); }
        @Override public List<BatchMismatch> checkBatchTotals(int limit) { throw new UnsupportedOperationException(); }
        @Override public List<BatchVO> merchantBatches(String entityNo) { throw new UnsupportedOperationException(); }
        @Override public List<BatchVO> opsBatches(String status, String entityNo) { throw new UnsupportedOperationException(); }
        @Override public BatchVO approve(String batchNo, String operator, String remark) { throw new UnsupportedOperationException(); }
        @Override public BatchVO hold(String batchNo, String operator, String reason) { throw new UnsupportedOperationException(); }
        @Override public Preview preview(Long from) { throw new UnsupportedOperationException(); }
    }

    private StlBill givenBill(String suffix, long completedAt) {
        String subNo = "SUB-BJOB-" + suffix;
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO ord_status_log (sub_order_no, status, at, tenant_no, created_at)"
                        + " VALUES (?, 'COMPLETED', ?, 'MAIN', CURRENT_TIMESTAMP)", subNo, completedAt));
        StlBill b = new StlBill();
        b.setSettleNo("STL-BJOB-" + suffix);
        b.setSubOrderNo(subNo);
        b.setOrderNo("SO-BJOB-" + suffix);
        b.setEntityNo(ENTITY);
        b.setPayChannel("WECHAT");
        b.setStatus(StlBill.PENDING);
        b.setGrossMinor(10000L);
        b.setNetMinor(9500L);
        b.setCommissionMinor(500L);
        b.setAccruedAt(completedAt);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
        return b;
    }

    private StlBill reload(String settleNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo).last("LIMIT 1")));
    }
}
