package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.StubSplitGateway;
import ai.neargo.shop.pay.settle.SplitPreparationService;
import ai.neargo.shop.spi.points.PointsRefundPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 5.3 分账按退款金额回退（口径 A）· 真库。
 *
 * <p>结算单：商品实付 9000、商家自寄运费 0；佣金 450、服务费 90 → 商家所得 net = 9000 - 450 - 90 = 8460。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("结算 · 分账按额回退")
class SettlementPartialReverseTest {

    @Autowired
    private SettlePort settlePort;
    @Autowired
    private SplitPreparationService splits;
    @MockitoBean
    private PointsRefundPort pointsRefunds;
    // These cases exercise settlement holds; cash terminal-proof validation has its own flow tests.
    @MockitoBean
    private ai.neargo.shop.pay.service.impl.RefundCapacityLedger refundCapacity;
    @Autowired
    private StubSplitGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    private void givenBill(String sub, String status) {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO stl_bill (settle_no, sub_order_no, order_no, entity_no, pay_merchant_no, pay_channel, status,"
                        + " gross_minor, commission_minor, service_fee_minor, net_minor, split_amount_minor,"
                        + " freight_income_minor, freight_cost_minor, freight_ship_mode, accrued_at,"
                        + " tenant_no, created_at, updated_at, version, deleted)"
                        + " VALUES (?, ?, ?, 'M0001', 'SYNTHETIC-PARTIAL-REFUND', 'WECHAT', ?, 9000, 450, 90, 8460, 8460, 0, 0, 'MERCHANT_SELF', ?,"
                        + " 'MAIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                "STL-" + sub, sub, "P-" + sub, status, System.currentTimeMillis()));
    }

    private Map<String, Object> bill(String sub) {
        return jdbc.queryForMap("SELECT status, net_minor, commission_minor, refunded_goods_minor, reversed_minor"
                + " FROM stl_bill WHERE sub_order_no = ?", sub);
    }

    private long reversedViaChannel(String sub) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM stl_split_log WHERE sub_order_no = ?"
                + " AND split_action = 'REVERSE' AND result = 'SUCCESS'", Long.class, sub);
    }

    @Test
    @DisplayName("★★★ 分账后分三次退完：每次按额回退、中途保持 SPLIT，Σ 回退 = 商家所得，最后一笔转 REVERSED")
    void postSplitThreeInstallments() {
        givenBill("SPR-1", "SPLIT");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-1", "AS-SPR-1a", 3000, 0, 9000))).isTrue();
        assertThat(bill("SPR-1")).containsEntry("STATUS", "SPLIT").containsEntry("REVERSED_MINOR", 2820L);
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-1", "AS-SPR-1b", 3000, 0, 9000))).isTrue();
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-1", "AS-SPR-1c", 3000, 0, 9000))).isTrue();
        assertThat(bill("SPR-1")).containsEntry("STATUS", "REVERSED").containsEntry("REFUNDED_GOODS_MINOR", 9000L);
        assertThat(reversedViaChannel("SPR-1")).isEqualTo(8460L);
    }

    @Test
    void pre_split_partial_refund_then_split_returns_the_entire_remaining_original_command() {
        givenBill("SPR-MIX","PENDING");
        jdbc.update("UPDATE stl_bill SET funds_mode='DIRECT',split_amount_minor=540 WHERE sub_order_no='SPR-MIX'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-MIX","AS-SPR-MIX-0",3000,0,9000))).isTrue();
        assertThat(jdbc.queryForObject("SELECT split_amount_minor FROM stl_bill WHERE sub_order_no='SPR-MIX'",Long.class)).isEqualTo(360);
        // The remaining frozen cash command was accepted after that first refund.
        jdbc.update("UPDATE stl_bill SET status='SPLIT',split_at=1 WHERE sub_order_no='SPR-MIX'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-MIX","AS-SPR-MIX-1",3000,0,9000))).isTrue();
        assertThat(reversedViaChannel("SPR-MIX")).isEqualTo(180);
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-MIX","AS-SPR-MIX-2",3000,0,9000))).isTrue();
        assertThat(reversedViaChannel("SPR-MIX")).isEqualTo(360);
    }

    @Test
    void direct_fee_split_returns_only_original_split_amount_across_partial_refunds() {
        givenBill("SPR-DIRECT","SPLIT");
        jdbc.update("UPDATE stl_bill SET funds_mode='DIRECT',split_amount_minor=540 WHERE sub_order_no='SPR-DIRECT'");
        for(int i=0;i<3;i++) {
            assertThat(settlePort.reverseSplit(new SplitReversal("SPR-DIRECT","AS-SPR-DIRECT-"+i,3000,0,9000))).isTrue();
            assertThat(reversedViaChannel("SPR-DIRECT")).isEqualTo(180L*(i+1));
        }
        assertThat(bill("SPR-DIRECT")).containsEntry("STATUS","REVERSED").containsEntry("REVERSED_MINOR",540L);
    }

    @Test
    @DisplayName("★★★ 分账前部分退：只扣结算单金额、状态不变（余款照常分账）；同一售后单重放不重复扣")
    void preSplitPartialKeepsSettleable() {
        givenBill("SPR-2", "PENDING");
        SplitReversal cmd = new SplitReversal("SPR-2", "AS-SPR-2", 3000, 0, 9000);
        assertThat(settlePort.reverseSplit(cmd)).isTrue();
        assertThat(settlePort.reverseSplit(cmd)).as("重放").isTrue();
        assertThat(bill("SPR-2")).containsEntry("STATUS", "PENDING")
                .containsEntry("NET_MINOR", 8460L - 2820L).containsEntry("COMMISSION_MINOR", 300L)
                .containsEntry("REFUNDED_GOODS_MINOR", 3000L).containsEntry("REVERSED_MINOR", 0L);
        assertThat(reversedViaChannel("SPR-2")).as("分账前不发通道指令").isZero();
    }

    @Test
    void prepared_refund_holds_split_without_changing_bill_before_cash_success() {
        givenBill("SPR-HOLD", "PENDING");
        SplitReversal cmd = new SplitReversal("SPR-HOLD", "AS-SPR-HOLD", 3000, 0, 9000);
        assertThat(settlePort.reversePreparedRefund(cmd)).isTrue();
        assertThat(settlePort.reversePreparedRefund(cmd)).isTrue();
        assertThat(bill("SPR-HOLD")).containsEntry("STATUS", "PENDING")
                .containsEntry("NET_MINOR", 8460L)
                .containsEntry("REFUNDED_GOODS_MINOR", 0L);
        assertThatThrownBy(() -> splits.prepare("STL-SPR-HOLD"))
                .hasMessage("SETTLEMENT_REFUND_PENDING");
    }

    @Test
    void prepared_refund_adjusts_only_after_confirmed_pay_proof() {
        givenBill("SPR-CONFIRM", "PENDING");
        SplitReversal cmd = new SplitReversal("SPR-CONFIRM", "AS-SPR-CONFIRM", 3000, 0, 9000);
        assertThat(settlePort.reversePreparedRefund(cmd)).isTrue();
        when(pointsRefunds.recoveryStatus("PREP", "HASH"))
                .thenReturn(new PointsRefundPort.RefundRecoveryStatus("PREP", cmd.afterSaleNo(),
                        cmd.subOrderNo(), "CONFIRMED", 50, "PAY", false));
        assertThat(settlePort.confirmPreparedRefund(cmd, "PREP", "HASH")).isTrue();
        assertThat(settlePort.confirmPreparedRefund(cmd, "PREP", "HASH")).isTrue();
        assertThat(bill("SPR-CONFIRM")).containsEntry("NET_MINOR", 5640L)
                .containsEntry("REFUNDED_GOODS_MINOR", 3000L);
        assertThat(jdbc.queryForObject("SELECT status FROM stl_points_refund_hold WHERE after_sale_no=?",
                String.class, cmd.afterSaleNo())).isEqualTo("CONFIRMED");
    }

    @Test
    void compensated_cash_failure_releases_hold_without_changing_bill() {
        givenBill("SPR-CANCEL", "PENDING");
        SplitReversal cmd = new SplitReversal("SPR-CANCEL", "AS-SPR-CANCEL", 3000, 0, 9000);
        assertThat(settlePort.reversePreparedRefund(cmd)).isTrue();
        when(pointsRefunds.recoveryStatus("PREP", "HASH"))
                .thenReturn(new PointsRefundPort.RefundRecoveryStatus("PREP", cmd.afterSaleNo(),
                        cmd.subOrderNo(), "ABORTED", 50, "FAILED-PAY", false));
        assertThatThrownBy(() -> settlePort.cancelPreparedRefund(cmd, "PREP", "HASH"))
                .hasMessage("SETTLEMENT_REFUND_COMPENSATION_REQUIRED");
        assertThat(bill("SPR-CANCEL")).containsEntry("NET_MINOR", 8460L);
        when(pointsRefunds.recoveryStatus("PREP", "HASH"))
                .thenReturn(new PointsRefundPort.RefundRecoveryStatus("PREP", cmd.afterSaleNo(),
                        cmd.subOrderNo(), "ABORTED", 50, "FAILED-PAY", true));
        assertThat(settlePort.cancelPreparedRefund(cmd, "PREP", "HASH")).isTrue();
        assertThat(settlePort.cancelPreparedRefund(cmd, "PREP", "HASH")).isTrue();
        assertThat(bill("SPR-CANCEL")).containsEntry("NET_MINOR", 8460L)
                .containsEntry("REFUNDED_GOODS_MINOR", 0L);
        assertThat(jdbc.queryForObject("SELECT status FROM stl_points_refund_hold WHERE after_sale_no=?",
                String.class, cmd.afterSaleNo())).isEqualTo("CANCELLED");
    }

    @Test
    void failed_cash_without_collection_releases_hold_from_aborted_pay_proof() {
        givenBill("SPR-NO-COLLECTION", "PENDING");
        SplitReversal cmd = new SplitReversal("SPR-NO-COLLECTION", "AS-SPR-NO-COLLECTION", 3000, 0, 9000);
        assertThat(settlePort.reversePreparedRefund(cmd)).isTrue();
        when(pointsRefunds.recoveryStatus("PREP", "HASH"))
                .thenReturn(new PointsRefundPort.RefundRecoveryStatus("PREP", cmd.afterSaleNo(),
                        cmd.subOrderNo(), "ABORTED", 50, "FAILED-PAY", false, false));
        assertThat(settlePort.cancelPreparedRefund(cmd, "PREP", "HASH")).isTrue();
        assertThat(bill("SPR-NO-COLLECTION")).containsEntry("NET_MINOR", 8460L)
                .containsEntry("REFUNDED_GOODS_MINOR", 0L);
    }

    @Test
    @DisplayName("★★ 通道回退失败 → false 且转人工、不记账；修好后同一售后单可重试成功")
    void channelFailureStopsRefund() {
        givenBill("SPR-3", "SPLIT");
        gateway.failNext("SPR-3");
        SplitReversal cmd = new SplitReversal("SPR-3", "AS-SPR-3", 3000, 0, 9000);
        assertThat(settlePort.reverseSplit(cmd)).isFalse();
        assertThat(bill("SPR-3")).containsEntry("STATUS", "MANUAL").containsEntry("REFUNDED_GOODS_MINOR", 0L);
        jdbc.update("UPDATE stl_bill SET split_at = 1 WHERE sub_order_no = 'SPR-3'");
        assertThat(settlePort.reverseSplit(cmd)).isTrue();
        assertThat(reversedViaChannel("SPR-3")).isEqualTo(2820L);
    }
}
