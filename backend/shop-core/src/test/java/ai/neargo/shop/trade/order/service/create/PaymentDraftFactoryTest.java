package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.entity.OrdOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentDraftFactoryTest {

    private OrderGroup group(String merchant, long price, int qty) {
        return new OrderGroup(merchant, List.of(new CartLine("SK", qty, price, merchant, "ST")));
    }

    @Test
    @DisplayName("跨商家一次付：支付单聚合多张订单，金额=各订单之和")
    void multiMerchantOnePayment() {
        PaymentDraft draft = PaymentDraftFactory.build("PAY1", "U1", "C1",
                List.of(group("M0001", 4980L, 2), group("M0002", 5800L, 1)),
                n -> "PAY1-" + n);

        OrdOrder pay = draft.payment();
        assertThat(pay.getOrderNo()).isEqualTo("PAY1");
        assertThat(pay.getUserNo()).isEqualTo("U1");
        assertThat(pay.getCommunityNo()).isEqualTo("C1");
        assertThat(pay.getStatus()).isEqualTo(OrdOrder.WAIT_PAY);
        assertThat(pay.getPayAmount()).isEqualTo(4980L * 2 + 5800L);

        assertThat(draft.orders()).hasSize(2);
        assertThat(draft.orders().get(0).order().getSubOrderNo()).isEqualTo("PAY1-1");
        assertThat(draft.orders().get(0).order().getEntityNo()).isEqualTo("M0001");
        assertThat(draft.orders().get(1).order().getSubOrderNo()).isEqualTo("PAY1-2");
    }

    @Test
    @DisplayName("单商家：支付单恰含一张订单")
    void singleMerchant() {
        PaymentDraft draft = PaymentDraftFactory.build("PAY2", "U1", "C1",
                List.of(group("M0001", 1000L, 3)), n -> "PAY2-" + n);
        assertThat(draft.orders()).hasSize(1);
        assertThat(draft.payment().getPayAmount()).isEqualTo(3000L);
    }
}
