package ai.neargo.shop.trade.order.machine;

import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleMachine;
import ai.neargo.shop.trade.order.machine.order.OrderMachine;
import ai.neargo.shop.trade.order.machine.payment.PaymentMachine;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentMachine;
import ai.neargo.shop.trade.order.statemachine.Invariants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★★★ 四台机器的迁移表与《订单状态机-完整方案》§2–§5 <b>逐行相等</b>（TDD-订单状态机 AC3）。
 *
 * <p>比的是全集：多一行（表外组合被放行）、少一行（文档写了没实现）都红 —— 反例不用另写，
 * 「不在表里」就是反例。改规则时先改文档，再改这里，再改机器。
 */
class MachineTablesTest {

    private static final String E = "∅";

    @Test
    @DisplayName("支付单 P1–P6")
    void payment() {
        EdgeTable t = new EdgeTable()
                .row(List.of(E), "PLACE", List.of("BUYER", "PLATFORM"), "WAIT_PAY", "WAIT_OFFLINE_PAY")
                .row(List.of("WAIT_PAY"), "PAY_SUCCEEDED", List.of("CHANNEL"), "PAID")
                .row(List.of("WAIT_OFFLINE_PAY"), "OFFLINE_CONFIRMED", List.of("MERCHANT"), "PAID")
                .row(List.of("WAIT_PAY", "WAIT_OFFLINE_PAY"), "CANCEL", List.of("BUYER", "PLATFORM"), "CANCELLED")
                .row(List.of("WAIT_PAY"), "PAY_TIMEOUT", List.of("SYSTEM"), "CLOSED")
                .stay(List.of("CLOSED", "CANCELLED"), "LATE_PAY_SUCCEEDED", List.of("CHANNEL"));
        assertThat(Invariants.edges(PaymentMachine.MACHINE)).containsExactlyInAnyOrderElementsOf(t.edges());
    }

    @Test
    @DisplayName("订单 O1–O11（干预只到履约中 / 已完成，不碰钱）")
    void order() {
        EdgeTable t = new EdgeTable()
                .row(List.of("WAIT_PAY"), "PAY_CONFIRMED", List.of("SYSTEM"), "WAIT_FULFILL", "FULFILLING")
                .row(List.of("WAIT_PAY"), "CANCEL", List.of("SYSTEM"), "CANCELLED")
                .stay(List.of("WAIT_FULFILL"), "PREPARE", List.of("MERCHANT"))
                .row(List.of("WAIT_FULFILL"), "SHIPPED", List.of("SYSTEM"), "FULFILLING")
                .row(List.of("WAIT_FULFILL"), "SHIP_BY_MERCHANT", List.of("MERCHANT"), "FULFILLING")
                .row(List.of("WAIT_FULFILL"), "ARRIVED_AT_PICKUP", List.of("MERCHANT"), "FULFILLING")
                .row(List.of("WAIT_FULFILL", "FULFILLING"), "VERIFIED", List.of("MERCHANT"), "FULFILLING", "COMPLETED")
                .row(List.of("WAIT_FULFILL", "FULFILLING"), "DELIVERED_BY_MERCHANT", List.of("MERCHANT"), "COMPLETED")
                .row(List.of("WAIT_FULFILL", "FULFILLING"), "RECEIPT_CONFIRMED", List.of("BUYER"), "COMPLETED")
                .row(List.of("FULFILLING"), "RECEIPT_AUTO_CONFIRMED", List.of("SYSTEM"), "COMPLETED")
                .row(List.of("WAIT_FULFILL", "FULFILLING", "COMPLETED"), "FULLY_REFUNDED", List.of("SYSTEM"), "REFUNDED")
                .row(List.of("WAIT_FULFILL", "FULFILLING"), "INTERVENE", List.of("PLATFORM"), "FULFILLING", "COMPLETED");
        assertThat(Invariants.edges(OrderMachine.MACHINE)).containsExactlyInAnyOrderElementsOf(t.edges());
    }

    @Test
    @DisplayName("发货单 H1–H6")
    void shipment() {
        EdgeTable t = new EdgeTable()
                .row(List.of(E), "CREATE", List.of("MERCHANT"), "CREATED")
                .row(List.of("CREATED"), "PICKED_UP", List.of("CHANNEL"), "SHIPPED")
                .row(List.of("SHIPPED"), "SIGNED", List.of("CHANNEL"), "DELIVERED")
                .row(List.of("CREATED", "SHIPPED", "DELIVERED"), "RECEIPT_CONFIRMED", List.of("BUYER", "SYSTEM"), "RECEIVED")
                .row(List.of("CREATED", "SHIPPED", "DELIVERED"), "CORRECT_WAYBILL", List.of("MERCHANT", "PLATFORM"), "VOIDED")
                .row(List.of("CREATED"), "CANCEL", List.of("MERCHANT"), "VOIDED");
        assertThat(Invariants.edges(ShipmentMachine.MACHINE)).containsExactlyInAnyOrderElementsOf(t.edges());
    }

    @Test
    @DisplayName("售后单 A1–A21")
    void afterSale() {
        String money = "REFUNDING", offline = "WAIT_OFFLINE_REFUND";
        EdgeTable t = new EdgeTable()
                .row(List.of(E), "APPLY", List.of("BUYER"), "APPLIED")
                .row(List.of("APPLIED"), "PREPARATION_NOT_STARTED_REFUND", List.of("BUYER"), money)
                .row(List.of(E), "MERCHANT_INITIATE", List.of("MERCHANT"), money, offline)
                .row(List.of(E), "TRADE_VOID", List.of("SYSTEM"), money)
                .row(List.of("APPLIED"), "APPROVE", List.of("MERCHANT"), money, offline, "WAIT_RECEIPT", "WAIT_RETURN")
                .row(List.of("APPLIED"), "OFFER", List.of("MERCHANT"), "NEGOTIATING")
                .row(List.of("NEGOTIATING"), "ACCEPT_OFFER", List.of("BUYER"), money, offline, "WAIT_RETURN", "RETURNING")
                .row(List.of("NEGOTIATING"), "DECLINE_OFFER", List.of("BUYER"), "APPLIED")
                .row(List.of("NEGOTIATING"), "OFFER_EXPIRED", List.of("SYSTEM"), "APPLIED")
                .row(List.of("APPLIED"), "REJECT", List.of("MERCHANT"), "REJECTED")
                .row(List.of("WAIT_RECEIPT"), "CONFIRM_REFUND", List.of("MERCHANT"), money, offline)
                .row(List.of("WAIT_RETURN"), "RETURN_SHIPPED", List.of("BUYER"), "RETURNING")
                .row(List.of("WAIT_RETURN"), "RETURN_EXPIRED", List.of("SYSTEM"), "CLOSED")
                .row(List.of("RETURNING"), "RETURN_RECEIVED", List.of("MERCHANT"), money, offline)
                .row(List.of(money), "REFUND_SUCCEEDED", List.of("CHANNEL", "SYSTEM"), "REFUNDED")
                .stay(List.of(money), "REFUND_FAILED", List.of("CHANNEL"))
                .row(List.of("APPLIED", "NEGOTIATING", "WAIT_RETURN", "RETURNING", "WAIT_RECEIPT", "REJECTED",
                        offline, "OFFLINE_REFUNDED"), "ESCALATE", List.of("BUYER", "MERCHANT"), "ARBITRATING")
                .row(List.of("ARBITRATING"), "ARBITRATE_REFUND", List.of("PLATFORM"), money, offline, "WAIT_RETURN", "RETURNING")
                .row(List.of("ARBITRATING"), "ARBITRATE_CLOSE", List.of("PLATFORM"), "CLOSED")
                .row(List.of("APPLIED", "NEGOTIATING", "WAIT_RETURN", "REJECTED"), "WITHDRAW", List.of("BUYER"), "CLOSED")
                .row(List.of("REJECTED"), "ESCALATION_WINDOW_EXPIRED", List.of("SYSTEM"), "CLOSED")
                .row(List.of(offline), "OFFLINE_REFUND_REGISTERED", List.of("MERCHANT"), "OFFLINE_REFUNDED")
                .row(List.of("OFFLINE_REFUNDED"), "OFFLINE_REFUND_ACKED", List.of("BUYER"), "REFUNDED");
        assertThat(Invariants.edges(AfterSaleMachine.MACHINE)).containsExactlyInAnyOrderElementsOf(t.edges());
    }
}
