package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderStateStore;
import ai.neargo.shop.trade.order.transition.OrderTransitions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptHandlerTest {

    static class FakeRead implements ReceiptReadStore {
        OrdSubOrder order;
        final List<OrdShipment> shipments = new ArrayList<>();
        final List<OrdItem> items = new ArrayList<>();

        public OrdShipment findShipment(String no) {
            return shipments.stream().filter(s -> s.getShipmentNo().equals(no)).findFirst().orElse(null);
        }

        public List<OrdShipment> shipmentsOf(String subOrderNo) {
            return shipments;
        }

        public List<OrdItem> itemsOf(String subOrderNo) {
            return items;
        }

        public OrdSubOrder findOrder(String subOrderNo) {
            return order;
        }
    }

    static class FakeWrite implements ReceiptWriteStore {
        public void saveShipment(OrdShipment shipment) {
        }
    }

    static class FakeStateStore implements OrderStateStore {
        public void saveOrderStatus(OrdOrder order) {
        }

        public void saveSubStatus(OrdSubOrder sub) {
        }

        public void appendLog(OrdStatusLog log) {
        }
    }

    private FakeRead read;
    private ReceiptHandler handler;

    private OrdShipment ship(String no, String status) {
        OrdShipment s = new OrdShipment();
        s.setShipmentNo(no);
        s.setSubOrderNo("ORD1");
        s.setStatus(status);
        return s;
    }

    private OrdItem item(String sku, String shipmentNo) {
        OrdItem i = new OrdItem();
        i.setSkuNo(sku);
        i.setShipmentNo(shipmentNo);
        return i;
    }

    @BeforeEach
    void setUp() {
        read = new FakeRead();
        OrdSubOrder o = new OrdSubOrder();
        o.setSubOrderNo("ORD1");
        o.setStatus(OrdSubOrder.FULFILLING);
        read.order = o;
        handler = new ReceiptHandler(read, new FakeWrite(), new OrderTransitions(new FakeStateStore()));
    }

    @Test
    @DisplayName("唯一包裹确认收货 → 发货单 RECEIVED + 订单 COMPLETED")
    void singlePackageCompletes() {
        read.shipments.add(ship("SH1", OrdShipment.DELIVERED));
        read.items.add(item("SK1", "SH1"));
        handler.confirm("ORD1", "SH1", Operator.user("U1"));
        assertThat(read.shipments.get(0).getStatus()).isEqualTo(OrdShipment.RECEIVED);
        assertThat(read.order.getStatus()).isEqualTo(OrdSubOrder.COMPLETED);
    }

    @Test
    @DisplayName("多包裹只确认一个 → 该包裹收货，订单仍履约中")
    void partialReceiptKeepsFulfilling() {
        read.shipments.add(ship("SH1", OrdShipment.DELIVERED));
        read.shipments.add(ship("SH2", OrdShipment.DELIVERED));
        read.items.add(item("SK1", "SH1"));
        read.items.add(item("SK2", "SH2"));
        handler.confirm("ORD1", "SH1", Operator.user("U1"));
        assertThat(read.findShipment("SH1").getStatus()).isEqualTo(OrdShipment.RECEIVED);
        assertThat(read.order.getStatus()).isEqualTo(OrdSubOrder.FULFILLING);
    }

    @Test
    @DisplayName("幂等：已收货重复确认不报错、状态不变")
    void idempotent() {
        read.shipments.add(ship("SH1", OrdShipment.RECEIVED));
        read.items.add(item("SK1", "SH1"));
        handler.confirm("ORD1", "SH1", Operator.user("U1"));
        assertThat(read.order.getStatus()).isEqualTo(OrdSubOrder.FULFILLING);   // 未被推进（本次是空操作）
    }

    @Test
    @DisplayName("未发货的包裹不能确认收货")
    void cannotReceiveUnshipped() {
        read.shipments.add(ship("SH1", OrdShipment.CREATED));
        assertThatThrownBy(() -> handler.confirm("ORD1", "SH1", Operator.user("U1")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("发货单不属该订单 → NOT_FOUND")
    void wrongOrder() {
        OrdShipment other = ship("SH9", OrdShipment.DELIVERED);
        other.setSubOrderNo("OTHER");
        read.shipments.add(other);
        assertThatThrownBy(() -> handler.confirm("ORD1", "SH9", Operator.user("U1")))
                .isInstanceOf(BizException.class);
    }
}
