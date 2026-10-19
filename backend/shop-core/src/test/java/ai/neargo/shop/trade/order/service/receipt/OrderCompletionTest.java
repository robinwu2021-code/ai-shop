package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCompletionTest {

    private OrdItem item(String sku, String shipmentNo) {
        OrdItem i = new OrdItem();
        i.setSkuNo(sku);
        i.setShipmentNo(shipmentNo);
        i.setQty(1);
        i.setShippedQty(shipmentNo == null ? 0 : 1);
        return i;
    }

    private OrdShipment ship(String no, String status) {
        OrdShipment s = new OrdShipment();
        s.setShipmentNo(no);
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("所有行已发 + 唯一发货单已收货 → 完成")
    void complete() {
        assertThat(OrderCompletion.isComplete(
                List.of(item("SK1", "SH1")), List.of(ship("SH1", OrdShipment.RECEIVED)))).isTrue();
    }

    @Test
    @DisplayName("发货单仅签收未确认收货 → 未完成")
    void deliveredNotReceived() {
        assertThat(OrderCompletion.isComplete(
                List.of(item("SK1", "SH1")), List.of(ship("SH1", OrdShipment.DELIVERED)))).isFalse();
    }

    @Test
    @DisplayName("有未发货的行 → 未完成")
    void unshippedItem() {
        assertThat(OrderCompletion.isComplete(
                List.of(item("SK1", null)), List.of(ship("SH1", OrdShipment.RECEIVED)))).isFalse();
    }

    @Test
    @DisplayName("无发货单 → 未完成")
    void noShipment() {
        assertThat(OrderCompletion.isComplete(List.of(item("SK1", null)), List.of())).isFalse();
    }

    @Test
    @DisplayName("多包裹：全部收货 → 完成；一个没收 → 未完成")
    void multiPackage() {
        List<OrdItem> items = List.of(item("SK1", "SH1"), item("SK2", "SH2"));
        assertThat(OrderCompletion.isComplete(items,
                List.of(ship("SH1", OrdShipment.RECEIVED), ship("SH2", OrdShipment.RECEIVED)))).isTrue();
        assertThat(OrderCompletion.isComplete(items,
                List.of(ship("SH1", OrdShipment.RECEIVED), ship("SH2", OrdShipment.DELIVERED)))).isFalse();
    }

    @Test
    @DisplayName("作废发货单不参与判定")
    void voidedIgnored() {
        List<OrdItem> items = List.of(item("SK1", "SH2"));
        assertThat(OrderCompletion.isComplete(items,
                List.of(ship("SH1", OrdShipment.VOIDED), ship("SH2", OrdShipment.RECEIVED)))).isTrue();
    }
}
