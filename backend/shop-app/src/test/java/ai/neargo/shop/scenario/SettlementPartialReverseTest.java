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
                "INSERT INTO stl_bill (settle_no, sub_order_no, order_no, entity_no, pay_channel, status,"
                        + " gross_minor, commission_minor, service_fee_minor, net_minor, split_amount_minor,"
                        + " freight_income_minor, freight_cost_minor, freight_ship_mode, accrued_at,"
                        + " tenant_no, created_at, updated_at, version, deleted)"
                        + " VALUES (?, ?, ?, 'M0001', 'WECHAT', ?, 9000, 450, 90, 8460, 540, 0, 0, 'MERCHANT_SELF', ?,"
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
