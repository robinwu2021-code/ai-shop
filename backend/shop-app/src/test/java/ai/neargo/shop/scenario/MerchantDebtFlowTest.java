package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.service.DebtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家欠款：Z4 追偿的第二层。
 *
 * <p>这笔钱最会被商家争，所以每一条都盯着<b>「事后说不说得清」</b>：
 * 什么时候欠的、凭什么欠、从哪一批扣的。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantDebtFlowTest {

    private static final String ENTITY = "M-DEBT-T1";

    @Autowired
    private DebtService debtService;
    @Autowired
    private ai.neargo.shop.merchant.service.AdmissionService admissionService;
    @Autowired
    private JdbcTemplate jdbc;

    /** ⚠️ 欠款账户与流水都是全局表，留一条在库里会让别的用例的余额莫名其妙非零 */
    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("DELETE FROM mch_debt_txn WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM mch_debt WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM mch_deposit_txn WHERE merchant_no = ?", ENTITY);
            jdbc.update("DELETE FROM mch_deposit WHERE merchant_no = ?", ENTITY);
            return null;
        });
    }

    @Test
    @DisplayName("★★★ 幂等键是源单号 —— 售后事件重投一次，商家就凭空多欠一笔")
    void incurIsIdempotentBySourceNo() {
        debtService.incur(ENTITY, 12800, "REFUND", "AS-001", "退款追不回");
        debtService.incur(ENTITY, 12800, "REFUND", "AS-001", "退款追不回");
        debtService.incur(ENTITY, 12800, "REFUND", "AS-001", "退款追不回");

        assertThat(debtService.balanceOf(ENTITY)).isEqualTo(12800);
        assertThat(debtService.txns(ENTITY))
                .as("流水也只该有一条 —— 重复的流水会让对账时逐笔回放对不上")
                .hasSize(1);
    }

    @Test
    @DisplayName("★★★ 指不出源头的欠款不许记 —— 记下来之后没法向商家解释")
    void incurRequiresSource() {
        assertThatThrownBy(() -> debtService.incur(ENTITY, 100, "REFUND", null, "无源"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> debtService.incur(ENTITY, 100, "REFUND", "  ", "空白"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(debtService.balanceOf(ENTITY)).isZero();
    }

    @Test
    @DisplayName("★★★ 抵扣不超过这一批能放的钱 —— 超了就成了「从还没成交的下一批里预扣」")
    void offsetIsCappedByPayable() {
        debtService.incur(ENTITY, 10000, "REFUND", "AS-002", "退款追不回");

        long took = debtService.offset(ENTITY, 3000, "STB-001");

        assertThat(took).as("这一批只有 3000 可放，最多扣 3000").isEqualTo(3000);
        assertThat(debtService.balanceOf(ENTITY)).isEqualTo(7000);
    }

    @Test
    @DisplayName("★★★ 抵扣不超过欠款余额 —— 超了就是平台白扣商家的钱")
    void offsetIsCappedByDebt() {
        debtService.incur(ENTITY, 2000, "REFUND", "AS-003", "退款追不回");

        long took = debtService.offset(ENTITY, 100000, "STB-002");

        assertThat(took).isEqualTo(2000);
        assertThat(debtService.balanceOf(ENTITY)).isZero();
    }

    @Test
    @DisplayName("★★ 没有欠款时抵扣返回 0，且不建空账户 —— 空账户会让「有没有欠过」变得说不清")
    void offsetWithoutDebtIsNoop() {
        assertThat(debtService.offset(ENTITY, 5000, "STB-003")).isZero();

        Integer accounts = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_debt WHERE entity_no = ?", Integer.class, ENTITY));
        assertThat(accounts).isZero();
    }

    @Test
    @DisplayName("★★★ 流水有符号：产生为正、偿还为负 —— 靠 txnType 推方向等于把方向表达两遍")
    void txnAmountsAreSigned() {
        debtService.incur(ENTITY, 5000, "REFUND", "AS-004", "退款追不回");
        debtService.offset(ENTITY, 2000, "STB-004");

        var txns = debtService.txns(ENTITY);
        assertThat(txns).hasSize(2);
        // 倒序：最近的在前
        assertThat(txns.get(0).amountMinor()).as("偿还为负").isEqualTo(-2000);
        assertThat(txns.get(1).amountMinor()).as("产生为正").isEqualTo(5000);
    }

    @Test
    @DisplayName("★★★ 每一笔都留变动后余额 —— 对账要能逐笔回放，只有余额字段的账户是不可审计的")
    void everyTxnCarriesBalanceAfter() {
        debtService.incur(ENTITY, 5000, "REFUND", "AS-005", "第一笔");
        debtService.incur(ENTITY, 3000, "REFUND", "AS-006", "第二笔");
        debtService.offset(ENTITY, 1000, "STB-005");

        var txns = debtService.txns(ENTITY);
        assertThat(txns).hasSize(3);
        assertThat(txns.get(2).balanceAfterMinor()).as("5000").isEqualTo(5000);
        assertThat(txns.get(1).balanceAfterMinor()).as("5000+3000").isEqualTo(8000);
        assertThat(txns.get(0).balanceAfterMinor()).as("8000-1000").isEqualTo(7000);

        // 逐笔回放：把有符号的变动额累加，必须等于最终余额
        long replay = txns.stream().mapToLong(DebtService.TxnVO::amountMinor).sum();
        assertThat(replay)
                .as("流水累加要等于余额，对不上就说明有一笔没落流水")
                .isEqualTo(debtService.balanceOf(ENTITY));
    }

    @Test
    @DisplayName("★★★ 保证金抵扣必须记操作人 —— 动的是商家本金，没有操作人就没法追责")
    void depositOffsetRequiresOperator() {
        debtService.incur(ENTITY, 5000, "REFUND", "AS-008", "退款追不回");

        assertThatThrownBy(() -> debtService.offsetByDeposit(ENTITY, 1000, null, "抵扣", java.util.UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> debtService.offsetByDeposit(ENTITY, 1000, "  ", "抵扣", java.util.UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(debtService.balanceOf(ENTITY)).as("没扣成").isEqualTo(5000);
    }

    @Test
    @DisplayName("★★★ 保证金抵扣不超过**可用**余额 —— 冻结中的那部分正被别的争议占着")
    void depositOffsetRespectsFrozen() {
        debtService.incur(ENTITY, 100000, "REFUND", "AS-009", "退款追不回");
        admissionService.recordTxn(ENTITY, "PAY", 10000, "缴纳保证金", "test", java.util.UUID.randomUUID().toString());
        admissionService.recordTxn(ENTITY, "FREEZE", 8000, "理赔占用", "test", java.util.UUID.randomUUID().toString());

        /*
         * ⚠️ 前提要在**动作之前**断言。
         * 初稿把它放在 offsetByDeposit 之后，量到的是扣完之后的可用额（0），
         * 于是这条用例红了 —— 而代码是对的。**前提断言测的是前提，不是结果。**
         */
        assertThat(admissionService.deposit(ENTITY).availableMinor())
                .as("前提：可用 = 实缴 10000 - 占用 8000")
                .isEqualTo(2000);

        long took = debtService.offsetByDeposit(ENTITY, 100000, "ops-1", "抵扣欠款", java.util.UUID.randomUUID().toString());

        assertThat(took)
                .as("只能抵可用的那 2000，拿冻结部分去抵等于同一笔钱赔两次")
                .isEqualTo(2000);
        assertThat(debtService.balanceOf(ENTITY)).isEqualTo(98000);
    }

    @Test
    @DisplayName("★★ 两侧各留流水 —— 保证金侧 DEDUCT、欠款侧 DEPOSIT，从任一侧都能对回去")
    void depositOffsetWritesBothLedgers() {
        debtService.incur(ENTITY, 5000, "REFUND", "AS-010", "退款追不回");
        admissionService.recordTxn(ENTITY, "PAY", 10000, "缴纳保证金", "test", java.util.UUID.randomUUID().toString());

        debtService.offsetByDeposit(ENTITY, 3000, "ops-2", "抵扣欠款", java.util.UUID.randomUUID().toString());

        assertThat(debtService.txns(ENTITY).get(0).txnType()).isEqualTo("DEPOSIT");
        assertThat(debtService.txns(ENTITY).get(0).amountMinor()).as("偿还为负").isEqualTo(-3000);

        Integer deductions = DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_deposit_txn WHERE merchant_no = ? AND txn_type = 'DEDUCT'",
                Integer.class, ENTITY));
        assertThat(deductions).as("保证金侧也要有一条").isEqualTo(1);
        assertThat(admissionService.deposit(ENTITY).paidMinor()).isEqualTo(7000);
    }

    @Test
    @DisplayName("★★ 抵扣流水要记从哪一批扣的 —— 商家问「这 1000 扣哪了」，答得上才叫说得清")
    void offsetRecordsBatch() {
        debtService.incur(ENTITY, 5000, "REFUND", "AS-007", "退款追不回");
        debtService.offset(ENTITY, 1000, "STB-006");

        assertThat(debtService.txns(ENTITY).get(0).batchNo()).isEqualTo("STB-006");
    }
}
