package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.settle.SettleBatchService;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlSettleBatch;
import ai.neargo.shop.settle.mapper.SettleMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账期批次的前半段：定 T2 → 入批 → 截批。
 *
 * <p><b>这三步此前一步都不存在</b> —— 结算单生成之后没有任何东西推动它。
 * 所以每一条用例的对照都是「不做这一步会怎样」，而不是「做完了长什么样」。
 *
 * <p>直接造结算单而不走下单链路：这里要验的是<b>批次逻辑</b>，
 * 把整条交易链路拉进来的话，这个类会变成那条链路的镜子 ——
 * 它一红，第一反应会是去查下单，而问题在批次。
 */
@SpringBootTest
@ActiveProfiles("test")
class SettleBatchFlowTest {

    private static final String ENTITY = "M-BATCH-T1";
    private static final long DAY = 86400000L;

    @Autowired
    private SettleBatchService batchService;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private SettleMappers.SettleBatchMapper batchMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * ⚠️ <b>造的数据必须删干净。</b>结算单与批次都是全局表，
     * 留一张 PENDING 的单在库里，此后每一轮扫描都会捞到它 ——
     * 而别的用例的失败信息里不会有任何一个字提到这个类。
     */
    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("DELETE FROM stl_bill WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM stl_settle_batch WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM ord_status_log WHERE sub_order_no LIKE 'SUB-BATCH-%'");
            jdbc.update("DELETE FROM ord_after_sale WHERE sub_order_no LIKE 'SUB-BATCH-%'");
            jdbc.update("DELETE FROM stl_recon_diff WHERE payment_no LIKE 'STL-BATCH-%'");
            return null;
        });
    }

    /** 造一张待结算的第三方单，并在状态流水里记它 completedAt 完成 */
    private StlBill givenBill(String suffix, long completedAt, String afterSaleStatus) {
        String subNo = "SUB-BATCH-" + suffix;
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("INSERT INTO ord_status_log (sub_order_no, status, at, tenant_no, created_at)"
                    + " VALUES (?, 'COMPLETED', ?, 'MAIN', CURRENT_TIMESTAMP)", subNo, completedAt);
            if (afterSaleStatus != null) {
                jdbc.update("INSERT INTO ord_after_sale (after_sale_no, order_no, sub_order_no,"
                        + " user_no, entity_no, type, reason, status,"
                        + " tenant_no, created_at, updated_at, version, deleted)"
                        + " VALUES (?, ?, ?, 'U-BATCH', ?, 'REFUND_ONLY', '测试', ?,"
                        + " 'MAIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                        "AS-BATCH-" + suffix, "SO-BATCH-" + suffix, subNo, ENTITY, afterSaleStatus);
            }
            return null;
        });
        StlBill b = new StlBill();
        b.setSettleNo("STL-BATCH-" + suffix);
        b.setSubOrderNo(subNo);
        b.setOrderNo("SO-BATCH-" + suffix);
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

    @Test
    @DisplayName("★★★ 售后期过了才定 T2 —— 没过就定，等于售后期还没走完钱就排进了放款队列")
    void settleableOnlyAfterAfterSaleWindow() {
        long now = System.currentTimeMillis();
        givenBill("fresh", now - DAY, null);          // 1 天前完成，售后期 7 天，没过
        givenBill("ripe", now - 30 * DAY, null);      // 30 天前完成，过了

        batchService.markSettleable();

        assertThat(reload("STL-BATCH-fresh").getSettleableAt())
                .as("售后期没过就不该有 T2")
                .isNull();
        assertThat(reload("STL-BATCH-ripe").getSettleableAt())
                .as("过了才定")
                .isNotNull();
    }

    @Test
    @DisplayName("★★★ T2 = 完成时刻 + 售后期，**不是 now** —— 落 now 的话同一批单的到账日会不一样")
    void settleableIsComputedNotNow() {
        long completedAt = System.currentTimeMillis() - 30 * DAY;
        givenBill("calc", completedAt, null);

        batchService.markSettleable();

        long t2 = reload("STL-BATCH-calc").getSettleableAt();
        assertThat(t2)
                .as("T2 要能从完成时刻推出来，与这一轮什么时候跑无关")
                .isEqualTo(completedAt + 7 * DAY);
    }

    @Test
    @DisplayName("★★★ 售后没闭环的单不定 T2 —— 这是硬闸：解冻等于把争议中的钱先给了一方")
    void openAfterSaleBlocksSettleable() {
        long old = System.currentTimeMillis() - 30 * DAY;
        givenBill("disputed", old, "APPLIED");
        givenBill("closed", old, "CLOSED");

        batchService.markSettleable();

        assertThat(reload("STL-BATCH-disputed").getSettleableAt())
                .as("售后还开着，不许进入放款流程")
                .isNull();
        assertThat(reload("STL-BATCH-closed").getSettleableAt())
                .as("对照：售后已闭环的照常定 —— 否则上一条可能只是「谁都不定」")
                .isNotNull();
    }

    @Test
    @DisplayName("★★ 定 T2 幂等：再跑一轮不改已有的值")
    void markSettleableIsIdempotent() {
        givenBill("idem", System.currentTimeMillis() - 30 * DAY, null);

        batchService.markSettleable();
        long first = reload("STL-BATCH-idem").getSettleableAt();
        batchService.markSettleable();

        assertThat(reload("STL-BATCH-idem").getSettleableAt())
                .as("重算会让 T2 漂移，而 T2 一动应结日跟着动")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("★★★ 同一主体同一通道同一应结日**只开一批** —— 开两批就是给商家打两次钱")
    void sameDueDateSharesOneBatch() {
        long old = System.currentTimeMillis() - 30 * DAY;
        givenBill("a", old, null);
        givenBill("b", old, null);

        batchService.markSettleable();
        batchService.collectIntoBatches();

        String batchA = reload("STL-BATCH-a").getBatchNo();
        String batchB = reload("STL-BATCH-b").getBatchNo();
        assertThat(batchA).as("两单都要入批").isNotNull();
        assertThat(batchB).isEqualTo(batchA);

        Long batches = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectCount(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, ENTITY)));
        assertThat(batches).as("只该有一批").isEqualTo(1L);
    }

    @Test
    @DisplayName("★★★ 截批时才算合计与笔数 —— 收单期间维护的话，并发入批会丢更新且对不出来")
    void closeComputesTotals() {
        long old = System.currentTimeMillis() - 30 * DAY;
        givenBill("t1", old, null);
        givenBill("t2", old, null);

        batchService.markSettleable();
        batchService.collectIntoBatches();

        StlSettleBatch before = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, ENTITY).last("LIMIT 1")));
        assertThat(before.getStatus()).isEqualTo(StlSettleBatch.DRAFT);
        assertThat(before.getBillCount()).as("收单期间不维护合计").isZero();

        int closed = batchService.closeDueBatches();

        StlSettleBatch after = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, ENTITY).last("LIMIT 1")));
        assertThat(closed).isPositive();
        assertThat(after.getStatus()).isEqualTo(StlSettleBatch.COLLECTED);
        assertThat(after.getBillCount()).isEqualTo(2);
        assertThat(after.getGrossMinor()).isEqualTo(20000L);
        assertThat(after.getNetMinor()).isEqualTo(19000L);
    }

    @Test
    @DisplayName("★★★ 已截批的批次不再接新单 —— 塞进去的话它的合计与实际就对不上了")
    void closedBatchTakesNoMoreBills() {
        long old = System.currentTimeMillis() - 30 * DAY;
        givenBill("first", old, null);
        batchService.markSettleable();
        batchService.collectIntoBatches();
        batchService.closeDueBatches();

        String closedBatch = reload("STL-BATCH-first").getBatchNo();

        // 同一天完成的第二单，应结日与上一批相同
        givenBill("late", old, null);
        batchService.markSettleable();
        batchService.collectIntoBatches();

        assertThat(reload("STL-BATCH-late").getBatchNo())
                .as("不该被塞进已经截掉的那一批")
                .isNotEqualTo(closedBatch);

        StlSettleBatch closed = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getBatchNo, closedBatch).last("LIMIT 1")));
        assertThat(closed.getBillCount())
                .as("那一批的合计不该被后来的单改动")
                .isEqualTo(1);
    }

    /**
     * <b>写这条时发现 {@code pay_channel} 是 NOT NULL DEFAULT 'WECHAT'</b> ——
     * 所以「通道为空」在库里只可能是<b>空串</b>，不可能是 NULL。
     * 服务里那个 null 判断因此是纯防御（实体没落库时可能为 null），
     * 而真正会发生的是这一条。用 NULL 去测等于测了一个库不允许的状态。
     */
    @Test
    @DisplayName("★★★ 门 1：账不平的单**根本不进批**，并记一条差异 —— 进了再剔出来最难查")
    void unbalancedBillNeverEntersBatch() {
        long old = System.currentTimeMillis() - 30 * DAY;
        StlBill bad = givenBill("bad", old, null);
        // 把实得改大 3 分：基数 ≠ 佣金 + 服务费 + 积分费 + 手续费 + 实得
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "UPDATE stl_bill SET net_minor = net_minor + 3 WHERE settle_no = ?", bad.getSettleNo()));
        givenBill("good", old, null);

        batchService.markSettleable();
        batchService.collectIntoBatches();

        assertThat(reload("STL-BATCH-bad").getBatchNo())
                .as("不平的单不该入批")
                .isNull();
        assertThat(reload("STL-BATCH-good").getBatchNo())
                .as("对照：同批其他单照常走 —— 单据级差异只挂该单，不卡住整批")
                .isNotNull();

        Integer diffs = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM stl_recon_diff WHERE payment_no = ? AND status = 'PENDING'",
                Integer.class, "STL-BATCH-bad"));
        assertThat(diffs).as("要留下一条待处置，否则这单就静默消失了").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 差异幂等：连跑三轮只留一条 —— 不去重的话一天能生出几百条指向同一件事的")
    void identityDiffIsIdempotent() {
        long old = System.currentTimeMillis() - 30 * DAY;
        StlBill bad = givenBill("dup", old, null);
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "UPDATE stl_bill SET net_minor = net_minor + 3 WHERE settle_no = ?", bad.getSettleNo()));

        batchService.markSettleable();
        batchService.collectIntoBatches();
        batchService.collectIntoBatches();
        batchService.collectIntoBatches();

        Integer diffs = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM stl_recon_diff WHERE payment_no = ?",
                Integer.class, "STL-BATCH-dup"));
        assertThat(diffs).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 手续费承担方是平台时**不算进等式** —— 算进去的话每张这样的单都会「不平」")
    void platformBorneFeeIsNotInTheIdentity() {
        long old = System.currentTimeMillis() - 30 * DAY;
        StlBill b = givenBill("platfee", old, null);
        /*
         * 平台承担 20 分手续费：那笔钱没有从商家实得里扣，
         * 所以等式左边不该有它。写错的话这单会被判成「不平」而永远不进批 ——
         * 而账其实是对的。
         */
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "UPDATE stl_bill SET channel_fee_minor = 20, fee_bearer = 'PLATFORM'"
                        + " WHERE settle_no = ?", b.getSettleNo()));

        batchService.markSettleable();
        batchService.collectIntoBatches();

        assertThat(reload("STL-BATCH-platfee").getBatchNo())
                .as("平台承担的手续费不进等式，这单是平的，该入批")
                .isNotNull();
    }

    @Test
    @DisplayName("★★ 通道为空的单不入批 —— 批按通道分，没有通道就不知道按谁的账期与冻结窗口算")
    void billWithoutChannelStaysOut() {
        StlBill b = givenBill("nochan", System.currentTimeMillis() - 30 * DAY, null);
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "UPDATE stl_bill SET pay_channel = '' WHERE settle_no = ?", b.getSettleNo()));

        batchService.markSettleable();
        batchService.collectIntoBatches();

        assertThat(reload("STL-BATCH-nochan").getBatchNo()).isNull();
    }
}
