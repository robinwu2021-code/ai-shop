package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.StubSplitGateway;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private StubSplitGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    private void givenBill(String sub, String status) {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO stl_bill (settle_no, sub_order_no, order_no, entity_no, pay_channel, pay_scene, currency,"
                        + " funds_mode, fee_bearer, pay_merchant_no, status,"
                        + " gross_minor, commission_minor, service_fee_minor, net_minor, split_amount_minor,"
                        + " freight_income_minor, freight_cost_minor, freight_ship_mode, accrued_at,"
                        + " tenant_no, created_at, updated_at, version, deleted)"
                        + " VALUES (?, ?, ?, 'M0001', 'WECHAT', 'MP_WECHAT', 'CNY', 'DIRECT', 'PLATFORM', 'ORIGINAL-MCH',"
                        + " ?, 9000, 450, 90, 8460, 540, 0, 0, 'MERCHANT_SELF', ?,"
                        + " 'MAIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                "STL-" + sub, sub, "P-" + sub, status, System.currentTimeMillis()));
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,user_no,pay_channel,pay_scene,"
                + "amount_minor,currency,status,wx_appid,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'PAY',?,'SPR-BUYER','WECHAT','MP_WECHAT',9000,'CNY','SUCCESS','ORIGINAL-APP','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", "PAY-" + sub, "P-" + sub);
    }

    private Map<String, Object> bill(String sub) {
        return jdbc.queryForMap("SELECT status, net_minor, commission_minor, refunded_goods_minor,"
                + " refunded_freight_minor, reversed_minor"
                + " FROM stl_bill WHERE sub_order_no = ?", sub);
    }

    private long reversedViaChannel(String sub) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM stl_split_log WHERE sub_order_no = ?"
                + " AND split_action = 'REVERSE' AND result = 'SUCCESS'", Long.class, sub);
    }

    @Test
    void preSplitThreeRefundsExhaustOriginalMerchantIncome() {
        givenBill("SPR-4", "PENDING");
        jdbc.update("update stl_bill set gross_minor=300,net_minor=270,commission_minor=30,service_fee_minor=0,"
                + "split_amount_minor=30 where sub_order_no='SPR-4'");
        for (int i = 0; i < 3; i++) {
            assertThat(settlePort.reverseSplit(new SplitReversal("SPR-4", "AS-SPR-4-" + i, 100, 0, 300))).isTrue();
        }
        long sumMerchantCuts = jdbc.queryForObject("select sum(merchant_reverse_minor) from stl_bill_refund"
                + " where sub_order_no='SPR-4'", Long.class);
        assertThat(sumMerchantCuts).isEqualTo(270L);
        assertThat(bill("SPR-4")).containsEntry("NET_MINOR", 0L).containsEntry("COMMISSION_MINOR", 0L);
    }

    @Test
    @DisplayName("未确认分账资金结果：不把指令接受当作实际回退")
    void postSplitPendingDoesNotCloseOrInventRecovery() {
        givenBill("SPR-1", "SPLIT");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-1", "AS-SPR-1a", 9000, 0, 9000))).isFalse();
        assertThat(bill("SPR-1")).containsEntry("STATUS", "MANUAL").containsEntry("REVERSED_MINOR", 0L)
                .containsEntry("REFUNDED_GOODS_MINOR", 0L);
        assertThat(reversedViaChannel("SPR-1")).isZero();
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
    void completeGoodsRefundRetainsMerchantFreightUntilFreightRefund() {
        givenBill("SPR-3", "PENDING");
        jdbc.update("update stl_bill set freight_income_minor=800,net_minor=9260 where sub_order_no='SPR-3'");
        jdbc.update("update stl_payment set amount_minor=9800 where payment_no='PAY-SPR-3'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-3", "AS-SPR-3-g", 9000, 0, 9000))).isTrue();
        assertThat(bill("SPR-3")).containsEntry("STATUS", "PENDING").containsEntry("NET_MINOR", 800L);
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-3", "AS-SPR-3-f", 0, 800, 9000))).isTrue();
        assertThat(bill("SPR-3")).containsEntry("STATUS", "REVERSED").containsEntry("NET_MINOR", 0L)
                .containsEntry("REFUNDED_FREIGHT_MINOR", 800L);
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-3", "AS-SPR-3-f", 0, 800, 9000))).isTrue();
        assertThatThrownBy(() -> settlePort.reverseSplit(new SplitReversal("SPR-3", "AS-SPR-3-over", 0, 1, 9000)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void pendingSupplierPayoutCannotBeTreatedAsPreSplit() {
        givenBill("SPR-PAYOUT", "CONFIRMED");
        jdbc.update("insert into stl_payout(payout_no,batch_no,entity_no,amount_minor,bill_count,status,tenant_no,"
                + "created_at,updated_at,version,deleted) values('PO-SPR','BATCH-SPR','M0001',8460,1,'PENDING',"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)");
        jdbc.update("update stl_bill set payout_no='PO-SPR' where sub_order_no='SPR-PAYOUT'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-PAYOUT", "AS-SPR-PAYOUT", 3000, 0, 9000)))
                .isFalse();
        assertThat(bill("SPR-PAYOUT")).containsEntry("NET_MINOR", 8460L)
                .containsEntry("REFUNDED_GOODS_MINOR", 0L).containsEntry("STATUS", "MANUAL");
        assertThat(jdbc.queryForObject("select amount_minor from stl_payout where payout_no='PO-SPR'", Long.class))
                .isEqualTo(8460L);
    }

    @Test
    void overweightFreightShortfallMustNotBecomeNegativeAutomaticPayable() {
        givenBill("SPR-OVERWEIGHT", "PENDING");
        jdbc.update("update stl_bill set freight_income_minor=800,freight_cost_minor=1200,"
                + "freight_ship_mode='PLATFORM_CALL',net_minor=8060 where sub_order_no='SPR-OVERWEIGHT'");
        jdbc.update("update stl_payment set amount_minor=9800 where payment_no='PAY-SPR-OVERWEIGHT'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-OVERWEIGHT", "AS-SPR-OVERWEIGHT", 9000, 0, 9000)))
                .isFalse();
        assertThat(bill("SPR-OVERWEIGHT")).containsEntry("NET_MINOR", 8060L)
                .containsEntry("REFUNDED_GOODS_MINOR", 0L).containsEntry("STATUS", "MANUAL");
    }

    @Test
    void replayKeyCannotAuthorizeDifferentSubOrderOrAmount() {
        givenBill("SPR-REPLAY", "PENDING");
        givenBill("SPR-REPLAY-OTHER", "PENDING");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-REPLAY", "AS-SPR-REPLAY", 3000, 0, 9000)))
                .isTrue();
        assertThatThrownBy(() -> settlePort.reverseSplit(
                new SplitReversal("SPR-REPLAY-OTHER", "AS-SPR-REPLAY", 3000, 0, 9000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settlePort.reverseSplit(
                new SplitReversal("SPR-REPLAY", "AS-SPR-REPLAY", 1000, 0, 9000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyCutsWithoutCompleteOriginalAllocationMustNotContinueAutomatically() {
        givenBill("SPR-LEGACY-CUTS", "PENDING");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-LEGACY-CUTS", "AS-SPR-LEGACY-a", 3000, 0, 9000)))
                .isTrue();
        jdbc.update("update stl_bill_refund set cash_allocation=null,gross_cut_minor=null"
                + " where after_sale_no='AS-SPR-LEGACY-a'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-LEGACY-CUTS", "AS-SPR-LEGACY-b", 3000, 0, 9000)))
                .isFalse();
        assertThat(bill("SPR-LEGACY-CUTS")).containsEntry("NET_MINOR", 5640L)
                .containsEntry("STATUS", "MANUAL").containsEntry("REFUNDED_GOODS_MINOR", 3000L);
    }

    @Test
    void zeroMerchantCutStillChecksInconsistentActualRecovery() {
        givenBill("SPR-POSITION", "PENDING");
        jdbc.update("update stl_bill set freight_income_minor=800,freight_cost_minor=800,"
                + "freight_ship_mode='PLATFORM_CALL' where sub_order_no='SPR-POSITION'");
        jdbc.update("update stl_payment set amount_minor=9800 where payment_no='PAY-SPR-POSITION'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-POSITION", "AS-SPR-POSITION-base", 0, 0, 9000)))
                .isTrue();
        jdbc.update("update stl_bill set status='SPLIT_CONFIRMED',merchant_paid_minor=8460,reversed_minor=9000"
                + " where sub_order_no='SPR-POSITION'");
        assertThat(settlePort.reverseSplit(new SplitReversal("SPR-POSITION", "AS-SPR-POSITION-f", 0, 800, 9000)))
                .isFalse();
        assertThat(bill("SPR-POSITION")).containsEntry("STATUS", "MANUAL")
                .containsEntry("REFUNDED_FREIGHT_MINOR", 0L);
    }

}
