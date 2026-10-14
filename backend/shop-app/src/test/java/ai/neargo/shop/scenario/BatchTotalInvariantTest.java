package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import ai.neargo.shop.pay.mapper.SettleMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>R6：批次合计 ≡ 其下结算单之和</b>（S10 · 2026-09-02）。
 *
 * <h2>这条规则此前只有「写入路径」</h2>
 * 合计是在截批那一刻算完写死的。写对了当然一致 ——
 * 而写错了<b>没有任何东西会发现</b>：放款按合计走、明细页按结算单算，
 * 两处各自都「对」，只有把它们摆在一起才看得出差。
 *
 * <p>这类账目错误<b>不报错、只是数字不对</b>，而它错的是「给商家打多少钱」。
 */
@SpringBootTest
@ActiveProfiles("test")
class BatchTotalInvariantTest {

    @Autowired
    private SettleBatchService batchService;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private SettleMappers.SettleBatchMapper batchMapper;

    private static int seq = 0;

    private String entity() {
        return "M-R6-" + (++seq);
    }

    private StlBill bill(String entityNo, long netMinor) {
        StlBill b = new StlBill();
        b.setSettleNo("STL-R6-" + (++seq));
        b.setSubOrderNo("SUB-R6-" + seq);
        b.setOrderNo("OD-R6-" + seq);
        b.setEntityNo(entityNo);
        b.setPayChannel("WECHAT");
        b.setCurrency("CNY");
        b.setGrossMinor(netMinor);
        b.setNetMinor(netMinor);
        b.setStatus(StlBill.PENDING);
        b.setSettleableAt(1_700_000_000_000L);
        b.setAccruedAt(1_700_000_000_000L);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
        return b;
    }

    /** 截出一个批次并返回它 */
    private StlSettleBatch closedBatch(String entityNo, long... nets) {
        for (long n : nets) {
            bill(entityNo, n);
        }
        batchService.collectIntoBatches();
        batchService.closeDueBatches();
        return DataScopeContext.executeWithoutScope(() -> batchMapper.selectOne(
                Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, entityNo).last("LIMIT 1")));
    }

    private List<SettleBatchService.BatchMismatch> check() {
        return batchService.checkBatchTotals(500);
    }

    @Test
    @DisplayName("★★★ 合计被改坏时要报出来 —— 放款按它走，错了就是给商家打错钱")
    void tamperedTotalIsDetected() {
        String e = entity();
        StlSettleBatch batch = closedBatch(e, 10_000L, 20_000L);
        assertThat(batch).as("前置：批次没截出来，下面测的就不是这件事").isNotNull();

        // 前置对照量：这一刻应当是平的
        assertThat(check()).extracting(SettleBatchService.BatchMismatch::batchNo)
                .as("刚截的批次就对不上 —— 那是截批本身的问题，不是这条巡检要测的")
                .doesNotContain(batch.getBatchNo());

        // 把合计改坏（模拟并发丢更新 / 手工改库 / 截批算错）
        StlSettleBatch patch = new StlSettleBatch();
        patch.setId(batch.getId());
        patch.setNetMinor(99_999L);
        DataScopeContext.executeWithoutScope(() -> batchMapper.updateById(patch));

        var found = check().stream()
                .filter(m -> m.batchNo().equals(batch.getBatchNo())).findFirst();
        assertThat(found)
                .as("合计从 30000 被改成 99999 而巡检没发现 —— "
                        + "放款会按 99999 打出去，而明细页显示 30000，两处各自都「对」")
                .isPresent();
        assertThat(found.get().billsNetMinor()).isEqualTo(30_000L);
        assertThat(found.get().batchNetMinor()).isEqualTo(99_999L);
        assertThat(found.get().diffMinor()).isEqualTo(69_999L);
    }

    @Test
    @DisplayName("★★★ 单数被改坏也要报 —— 合计对而单数错，意味着有单被漏算或多算")
    void tamperedCountIsDetected() {
        String e = entity();
        StlSettleBatch batch = closedBatch(e, 5_000L, 5_000L);

        StlSettleBatch patch = new StlSettleBatch();
        patch.setId(batch.getId());
        patch.setBillCount(99);
        DataScopeContext.executeWithoutScope(() -> batchMapper.updateById(patch));

        assertThat(check()).extracting(SettleBatchService.BatchMismatch::batchNo)
                .contains(batch.getBatchNo());
    }

    @Test
    @DisplayName("★★★ 正常批次不能被报成差异 —— 恒红的告警等于没有告警")
    void healthyBatchIsNotReported() {
        String e = entity();
        StlSettleBatch batch = closedBatch(e, 7_700L, 2_300L);

        assertThat(check()).extracting(SettleBatchService.BatchMismatch::batchNo)
                .as("正常批次被报成差异 —— 每轮都报几十条假差异的话，"
                        + "运营会学会忽略它，而真差异也就跟着被忽略了")
                .doesNotContain(batch.getBatchNo());
    }

    @Test
    @DisplayName("★★ 未截批的（DRAFT）不参与核验 —— 它的合计恒为 0，那是设计不是缺陷")
    void draftBatchIsSkipped() {
        String e = entity();
        bill(e, 1_000L);
        batchService.collectIntoBatches();   // 只收单，不截批

        StlSettleBatch draft = DataScopeContext.executeWithoutScope(() -> batchMapper.selectOne(
                Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, e).last("LIMIT 1")));
        assertThat(draft.getStatus()).as("前置：这一批应当还是草稿").isEqualTo(StlSettleBatch.DRAFT);

        assertThat(check()).extracting(SettleBatchService.BatchMismatch::batchNo)
                .as("DRAFT 的合计恒为 0（合计在截批那一刻才算）—— "
                        + "拿它去比必然「不一致」，而那是设计")
                .doesNotContain(draft.getBatchNo());
    }

    @Test
    @DisplayName("★★★ 混进批里的错币种单不算差异 —— 截批跳过它，巡检也要跳，否则在报自己的差异")
    void foreignCurrencyBillIsNotCountedAsMismatch() {
        String e = entity();
        StlSettleBatch batch = closedBatch(e, 10_000L);
        assertThat(batch.getCurrency()).isEqualTo("CNY");

        /*
         * 硬塞一笔台币单进这个人民币批 —— 与截批那条用例同一手法。
         *
         * 截批时它被跳过（不计入合计与单数），所以巡检也必须跳过。
         * 不跳的话巡检会算出「结算单之和多了 99999」并报差异，
         * <b>而少算才是对的</b> —— 那时巡检报的是它自己与截批的口径差，
         * 不是账的差。这种告警查下去会一直查到截批逻辑上，而那里没问题。
         */
        StlBill sneaky = bill(e, 99_999L);
        StlBill patch = new StlBill();
        patch.setId(sneaky.getId());
        patch.setCurrency("TWD");
        patch.setBatchNo(batch.getBatchNo());
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(patch));

        assertThat(check()).extracting(SettleBatchService.BatchMismatch::batchNo)
                .as("巡检把错币种的单算进了「结算单之和」—— 于是报出一条"
                        + "「批次少算了 99999」的假差异，而截批跳过它是对的。"
                        + "两处口径不一致时，巡检报的是自己的差异")
                .doesNotContain(batch.getBatchNo());
    }
}
