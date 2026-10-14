package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.entity.OrdOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentDraftFactoryTest {

    private OrderGroup group(String merchant, String store, long price, int qty) {
        return new OrderGroup(merchant, store, List.of(new CartLine("SK", qty, price, merchant, store)));
    }

    @Test
    @DisplayName("一次付聚合多张订单(按门店)，金额=各订单之和")
    void multiOrderOnePayment() {
        PaymentDraft draft = PaymentDraftFactory.build("PAY1", "U1", "C1",
                List.of(group("M0001", "ST-A", 4980L, 2), group("M0001", "ST-B", 5800L, 1)),
                n -> "PAY1-" + n);

        OrdOrder pay = draft.payment();
        assertThat(pay.getOrderNo()).isEqualTo("PAY1");
        assertThat(pay.getStatus()).isEqualTo(OrdOrder.WAIT_PAY);
        assertThat(pay.getPayAmount()).isEqualTo(4980L * 2 + 5800L);

        assertThat(draft.orders()).hasSize(2);
        assertThat(draft.orders().get(0).order().getSubOrderNo()).isEqualTo("PAY1-1");
        assertThat(draft.orders().get(0).order().getStoreNo()).isEqualTo("ST-A");
        assertThat(draft.orders().get(1).order().getStoreNo()).isEqualTo("ST-B");
    }

    @Test
    @DisplayName("单门店：支付单恰含一张订单")
    void singleStore() {
        PaymentDraft draft = PaymentDraftFactory.build("PAY2", "U1", "C1",
                List.of(group("M0001", "ST-A", 1000L, 3)), n -> "PAY2-" + n);
        assertThat(draft.orders()).hasSize(1);
        assertThat(draft.payment().getPayAmount()).isEqualTo(3000L);
    }
}
