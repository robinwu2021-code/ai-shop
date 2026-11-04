package ai.neargo.commerce.core.mall;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.mall.*;

import org.junit.jupiter.api.Test;

class OrderStateTest {
    @Test
    void projectsSeparatePaymentDeliveryAndRefundAxes() {
        assertEquals(
                OrderStatus.AWAITING_PAYMENT,
                OrderState.parse("UNPAID", "PENDING", "NONE").status());
        assertEquals(
                OrderStatus.PENDING_INVENTORY,
                OrderState.parse("UNPAID", "WAIT_INVENTORY", "NONE").status());
        assertEquals(
                OrderStatus.CANCEL_PENDING,
                OrderState.parse("CANCEL_PENDING", "PENDING", "NONE").status());
        assertEquals(
                OrderStatus.PAYMENT_UNKNOWN,
                OrderState.parse("PAYMENT_UNKNOWN", "PENDING", "NONE").status());
        assertEquals(OrderStatus.PAID, OrderState.parse("PAID", "PENDING", "NONE").status());
        assertEquals(
                OrderStatus.FULFILLING, OrderState.parse("PAID", "PROCESSING", "NONE").status());
        assertEquals(OrderStatus.COMPLETED, OrderState.parse("PAID", "COMPLETED", "NONE").status());
        assertEquals(
                OrderStatus.REFUNDING, OrderState.parse("PAID", "COMPLETED", "REFUNDING").status());
        assertEquals(
                OrderStatus.REFUNDED, OrderState.parse("PAID", "COMPLETED", "REFUNDED").status());
        assertEquals(OrderStatus.CLOSED, OrderState.parse("CLOSED", "PENDING", "NONE").status());
    }

    @Test
    void impossibleOrUnknownAxesFailClosed() {
        for (var axes :
                new String[][] {
                    {"UNPAID", "COMPLETED", "NONE"},
                    {"PAID", "WAIT_INVENTORY", "NONE"},
                    {"CLOSED", "COMPLETED", "NONE"},
                    {"UNPAID", "PENDING", "REFUNDED"},
                    {"invented", "PENDING", "NONE"},
                    {"UNPAID", "AWAITING_PAYMENT", "NONE"}
                })
            assertEquals(
                    "INVALID_ORDER_STATE",
                    assertThrows(
                                    MallException.class,
                                    () -> OrderState.parse(axes[0], axes[1], axes[2]))
                            .code());
    }
}
