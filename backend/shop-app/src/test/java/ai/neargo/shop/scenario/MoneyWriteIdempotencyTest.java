package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchDepositTxn;
import ai.neargo.shop.merchant.service.AdmissionService;
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
 * 运营手工动钱的写操作要幂等（S15 · V299）。
 *
 * <h2>缺口比设计册说的窄得多</h2>
 * 设计册写的是「真正动钱的写操作一处都没有幂等」。量了一遍，<b>那句话不对</b>：
 * <ul>
 *   <li>状态机型（放款、确认、提现审批、分账回退）—— 都先判终态再动手，已幂等；</li>
 *   <li>源单驱动型（欠款计提按 {@code source_no}、积分发放按台账行、
 *       退款按售后单号）—— 也已幂等。</li>
 * </ul>
 *
 * <p>真正的缺口是<b>运营手工输入金额的累加写</b>：没有状态可守（流水只增不改），
 * 也没有源单可依（金额是人当场填的）。它一共两处，都在这组里。
 *
 * <h2>为什么不用 Idempotency-Key 头</h2>
 * 那个执行器<b>没带 key 时直接放行</b> —— 接上了也可能一直不生效，
 * 而「以为接了其实没接」比没接更糟：服务端看不出来，测试也测不出来。
 * 放在列上有唯一索引兜着，漏传当场 400。
 */
@SpringBootTest
@ActiveProfiles("test")
class MoneyWriteIdempotencyTest {

    private static final String ENTITY = "M-IDEM-MONEY";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private DebtService debtService;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM mch_deposit_txn WHERE merchant_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_deposit WHERE merchant_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_debt_txn WHERE entity_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_debt WHERE entity_no = ?", ENTITY);
    }

    private long paidMinor() {
        var rows = jdbc.queryForList(
                "SELECT paid_minor FROM mch_deposit WHERE merchant_no = ?", Long.class, ENTITY);
        return rows.isEmpty() ? 0L : rows.getFirst();
    }

    private int txnCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM mch_deposit_txn WHERE merchant_no = ?", Integer.class, ENTITY);
    }

    @Test
    @DisplayName("★★★ 同一个键提交两次只记一笔 —— 金额是运营当场填的，点两次就是两倍")
    void sameKeyRecordsOnce() {
        String key = "REQ-DUP-1";
        admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 200_000L, "缴纳", "OPS", key);
        admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 200_000L, "缴纳", "OPS", key);

        assertThat(txnCount()).as("记了两笔流水").isEqualTo(1);
        assertThat(paidMinor())
                .as("实缴变成了两倍 —— 这张表只增不改，没有任何东西会把它改回来")
                .isEqualTo(200_000L);
    }

    @Test
    @DisplayName("★★★ 不同的键各记一笔 —— 幂等不能把两次真实的缴纳吃掉一次")
    void differentKeysBothRecorded() {
        admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 100_000L, "首次", "OPS", "REQ-A");
        admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 100_000L, "补缴", "OPS", "REQ-B");

        assertThat(txnCount())
                .as("按金额或按内容去重的话，商家真的缴两次同样的钱就会少记一笔")
                .isEqualTo(2);
        assertThat(paidMinor()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("★★★ 漏传幂等键当场拒 —— 静默放行的话「接没接上」在服务端看不出来")
    void missingKeyIsRejected() {
        assertThatThrownBy(() ->
                admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 100_000L, "缴纳", "OPS", null))
                .as("放行的话这个接口在「端上忘了传」时与没接幂等一模一样")
                .isNotNull();
        assertThatThrownBy(() ->
                admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 100_000L, "缴纳", "OPS", "  "))
                .isNotNull();

        assertThat(txnCount()).as("被拒的调用不该留下痕迹").isZero();
    }

    @Test
    @DisplayName("★★★ 保证金抵欠款重复提交不再接着扣 —— 它算 min(欠款,请求额,可用)，每次都变小")
    void offsetByDepositIsIdempotent() {
        admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 500_000L, "缴纳", "OPS", "REQ-SEED");
        debtService.incur(ENTITY, 300_000L, "SETTLE", "SRC-IDEM-1", "欠款");

        String key = "REQ-OFFSET-1";
        long first = debtService.offsetByDeposit(ENTITY, 100_000L, "OPS", "抵扣", key);
        long second = debtService.offsetByDeposit(ENTITY, 100_000L, "OPS", "抵扣", key);

        assertThat(first).isEqualTo(100_000L);
        assertThat(second)
                .as("第二次又扣了 —— 这个动作不是自然幂等的：三个数都变小了，"
                        + "它会接着扣，而每一次单看都「算得对」")
                .isZero();
        assertThat(debtService.balanceOf(ENTITY))
                .as("欠款被多抵了一次").isEqualTo(200_000L);
        assertThat(paidMinor())
                .as("保证金被多扣了一次 —— 动的是商家的本金")
                .isEqualTo(400_000L);
    }

    @Test
    @DisplayName("★★ 两个商家可以用同一个键 —— 唯一索引带归属，否则会跨商家误判为重复")
    void keyIsScopedPerOwner() {
        String other = ENTITY + "-2";
        try {
            admissionService.recordTxn(ENTITY, MchDepositTxn.PAY, 100_000L, "缴纳", "OPS", "REQ-SHARED");
            admissionService.recordTxn(other, MchDepositTxn.PAY, 100_000L, "缴纳", "OPS", "REQ-SHARED");

            assertThat(txnCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mch_deposit_txn WHERE merchant_no = ?", Integer.class, other))
                    .as("唯一索引只按 request_no 的话，B 商家的缴纳会被 A 的键吃掉")
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM mch_deposit_txn WHERE merchant_no = ?", other);
            jdbc.update("DELETE FROM mch_deposit WHERE merchant_no = ?", other);
        }
    }
}
