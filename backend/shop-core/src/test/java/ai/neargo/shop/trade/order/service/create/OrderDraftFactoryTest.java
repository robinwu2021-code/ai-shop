package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDraftFactoryTest {

    @Test
    @DisplayName("从一组建订单：商家/状态/商品额，行带供货门店与支付单归属")
    void buildsOrderAndItems() {
        OrderGroup group = new OrderGroup("M0001", List.of(
                new CartLine("SK1", 2, 4980L, "M0001", "ST-A"),
                new CartLine("SK2", 1, 5800L, "M0001", "ST-B")));

        OrderDraft draft = OrderDraftFactory.build("PAY1", "U1", "ORD1", group);

        OrdSubOrder o = draft.order();
        assertThat(o.getSubOrderNo()).isEqualTo("ORD1");
        assertThat(o.getOrderNo()).isEqualTo("PAY1");      // 指向支付单
        assertThat(o.getUserNo()).isEqualTo("U1");
        assertThat(o.getEntityNo()).isEqualTo("M0001");
        assertThat(o.getStatus()).isEqualTo(OrdSubOrder.WAIT_PAY);
        assertThat(o.getGoodsAmount()).isEqualTo(4980L * 2 + 5800L);
        assertThat(o.getPayAmount()).isEqualTo(4980L * 2 + 5800L);

        assertThat(draft.items()).hasSize(2);
        OrdItem first = draft.items().get(0);
        assertThat(first.getSubOrderNo()).isEqualTo("ORD1");
        assertThat(first.getOrderNo()).isEqualTo("PAY1");
        assertThat(first.getSkuNo()).isEqualTo("SK1");
        assertThat(first.getQty()).isEqualTo(2);
        assertThat(first.getAmount()).isEqualTo(9960L);
        assertThat(first.getSupplyStoreNo()).isEqualTo("ST-A");
        assertThat(first.getShippedQty()).isZero();
        // 同商家两店的行都在这一张订单里，门店在行上
        assertThat(draft.items()).extracting(OrdItem::getSupplyStoreNo).containsExactly("ST-A", "ST-B");
    }
}
