package ai.neargo.shop.trade.order.projection;

import ai.neargo.shop.trade.order.contract.ContractOrderStatus;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投影/聚合的单元测试。核心是 <b>封闭性</b>：任何库状态组合的投影结果都必须落在契约 7 值内 ——
 * 这正是现状没有、导致主单 {@code CLOSED} 漏成键名直出的那条保证。
 */
class OrderStatusProjectorTest {

    private static final List<String> MAIN = List.of(
            OrdOrder.WAIT_PAY, OrdOrder.PAID, OrdOrder.WAIT_OFFLINE_PAY,
            OrdOrder.CANCELLED, OrdOrder.CLOSED);

    private static final List<String> SUB = Arrays.asList(
            null, OrdSubOrder.WAIT_PAY, OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING,
            OrdSubOrder.COMPLETED, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED);

    // ---------------------------------------------------------------- 封闭性

    @Test
    @DisplayName("封闭性：contract(sub, main) 全组合 ∈ 契约 7 值")
    void contractIsClosed() {
        for (String main : MAIN) {
            for (String sub : SUB) {
                String r = OrderStatusProjector.contract(sub, main);
                assertThat(ContractOrderStatus.ALL)
                        .as("contract(sub=%s, main=%s)=%s 必须是契约值", sub, main, r)
                        .contains(r);
            }
        }
    }

    @Test
    @DisplayName("封闭性：aggregate(main, subs) 全组合 ∈ 契约 7 值")
    void aggregateIsClosed() {
        for (String main : MAIN) {
            for (String a : SUB) {
                if (a == null) {
                    continue;
                }
                assertThat(ContractOrderStatus.ALL)
                        .contains(OrderStatusProjector.aggregate(main, List.of(a)));
                for (String b : SUB) {
                    if (b == null) {
                        continue;
                    }
                    String r = OrderStatusProjector.aggregate(main, List.of(a, b));
                    assertThat(ContractOrderStatus.ALL)
                            .as("aggregate(main=%s, [%s,%s])=%s", main, a, b, r).contains(r);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 单包裹投影

    @Test
    @DisplayName("主单 CLOSED → 对外 CANCELLED（Q6，修 payView 直发 CLOSED）")
    void closedMapsToCancelled() {
        assertThat(OrderStatusProjector.contract(OrdSubOrder.CANCELLED, OrdOrder.CLOSED))
                .isEqualTo(ContractOrderStatus.CANCELLED);
        assertThat(OrderStatusProjector.contract(OrdSubOrder.WAIT_PAY, OrdOrder.CLOSED))
                .isEqualTo(ContractOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("WAIT_FULFILL → PAID（名字对齐）")
    void waitFulfillMapsToPaid() {
        assertThat(OrderStatusProjector.contract(OrdSubOrder.WAIT_FULFILL, OrdOrder.PAID))
                .isEqualTo(ContractOrderStatus.PAID);
    }

    @Test
    @DisplayName("线下待收款：主单 WAIT_OFFLINE_PAY + 子单待付 → WAIT_OFFLINE_PAY")
    void offlinePending() {
        assertThat(OrderStatusProjector.contract(OrdSubOrder.WAIT_PAY, OrdOrder.WAIT_OFFLINE_PAY))
                .isEqualTo(ContractOrderStatus.WAIT_OFFLINE_PAY);
        assertThat(OrderStatusProjector.contract(null, OrdOrder.WAIT_OFFLINE_PAY))
                .isEqualTo(ContractOrderStatus.WAIT_OFFLINE_PAY);
    }

    @Test
    @DisplayName("履约中/完成/退款原样透传")
    void passthrough() {
        assertThat(OrderStatusProjector.contract(OrdSubOrder.FULFILLING, OrdOrder.PAID))
                .isEqualTo(ContractOrderStatus.FULFILLING);
        assertThat(OrderStatusProjector.contract(OrdSubOrder.COMPLETED, OrdOrder.PAID))
                .isEqualTo(ContractOrderStatus.COMPLETED);
        assertThat(OrderStatusProjector.contract(OrdSubOrder.REFUNDED, OrdOrder.PAID))
                .isEqualTo(ContractOrderStatus.REFUNDED);
    }

    // ---------------------------------------------------------------- 聚合

    @Test
    @DisplayName("付款前跟主单：WAIT_PAY / WAIT_OFFLINE_PAY / CANCELLED / CLOSED→CANCELLED")
    void beforePaidFollowsMain() {
        assertThat(OrderStatusProjector.aggregate(OrdOrder.WAIT_PAY, List.of(OrdSubOrder.WAIT_PAY)))
                .isEqualTo(ContractOrderStatus.WAIT_PAY);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.WAIT_OFFLINE_PAY, List.of(OrdSubOrder.WAIT_PAY)))
                .isEqualTo(ContractOrderStatus.WAIT_OFFLINE_PAY);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.CANCELLED, List.of(OrdSubOrder.CANCELLED)))
                .isEqualTo(ContractOrderStatus.CANCELLED);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.CLOSED, List.of(OrdSubOrder.CANCELLED)))
                .isEqualTo(ContractOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("付款后取最落后的活跃子单（一家没发货整单显示待发货）")
    void paidTakesLaggingActive() {
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING)))
                .isEqualTo(ContractOrderStatus.PAID);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.FULFILLING, OrdSubOrder.FULFILLING)))
                .isEqualTo(ContractOrderStatus.FULFILLING);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.COMPLETED, OrdSubOrder.FULFILLING)))
                .isEqualTo(ContractOrderStatus.FULFILLING);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.COMPLETED, OrdSubOrder.COMPLETED)))
                .isEqualTo(ContractOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("活跃子单跳过已取消/已退款")
    void activeSkipsTerminal() {
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.REFUNDED, OrdSubOrder.FULFILLING)))
                .isEqualTo(ContractOrderStatus.FULFILLING);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.CANCELLED, OrdSubOrder.COMPLETED)))
                .isEqualTo(ContractOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("全部终态：有退款→REFUNDED，否则→CANCELLED")
    void allTerminal() {
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.REFUNDED, OrdSubOrder.CANCELLED)))
                .isEqualTo(ContractOrderStatus.REFUNDED);
        assertThat(OrderStatusProjector.aggregate(OrdOrder.PAID,
                List.of(OrdSubOrder.CANCELLED, OrdSubOrder.CANCELLED)))
                .isEqualTo(ContractOrderStatus.CANCELLED);
    }
}
