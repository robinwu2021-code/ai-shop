package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.transition.Operator;
import ai.neargo.shop.trade.order.transition.OrderStateStore;
import ai.neargo.shop.trade.order.transition.OrderTransitions;
import ai.neargo.shop.trade.entity.OrdOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipHandlerTest {

    static class FakeRead implements ShipmentReadStore {
        OrdSubOrder order;
        List<OrdItem> items = new ArrayList<>();
        OrdShipment dup;

        public OrdSubOrder findOrder(String subOrderNo) {
            return order != null && order.getSubOrderNo().equals(subOrderNo) ? order : null;
        }

        public List<OrdItem> itemsOf(String subOrderNo) {
            return items;
        }

        public OrdShipment findActiveWaybill(String carrier, String waybillNo) {
            return dup;
        }
    }

    static class FakeWrite implements ShipmentWriteStore {
        OrdShipment saved;
        List<OrdItem> bound;

        public void saveShipment(OrdShipment shipment) {
            saved = shipment;
        }

        public void bindItems(List<OrdItem> items) {
            bound = items;
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
    private FakeWrite write;
    private ShipHandler handler;

    private OrdItem item(String sku) {
        OrdItem i = new OrdItem();
        i.setSkuNo(sku);
        i.setQty(1);
        return i;
    }

    @BeforeEach
    void setUp() {
        read = new FakeRead();
        write = new FakeWrite();
        OrdSubOrder o = new OrdSubOrder();
        o.setSubOrderNo("ORD1");
        o.setStatus(OrdSubOrder.WAIT_FULFILL);
        o.setStoreNo("ST-A");
        read.order = o;
        read.items = new ArrayList<>(List.of(item("SK1"), item("SK2")));
        handler = new ShipHandler(read, write, new OrderTransitions(new FakeStateStore()), () -> "SH1");
    }

    @Test
    @DisplayName("整单发货：建发货单、绑定行、订单置 FULFILLING")
    void shipsWholeOrder() {
        String no = handler.ship("ORD1", "STO", "  773445089430765 ", null, Operator.merchant("M1"));
        assertThat(no).isEqualTo("SH1");
        assertThat(write.saved.getShipmentNo()).isEqualTo("SH1");
        assertThat(write.saved.getCarrier()).isEqualTo("STO");
        assertThat(write.saved.getWaybillNo()).isEqualTo("773445089430765");  // 已清洗去空格
        assertThat(write.bound).hasSize(2);
        assertThat(write.bound).allMatch(i -> "SH1".equals(i.getShipmentNo()));
        assertThat(read.order.getStatus()).isEqualTo(OrdSubOrder.FULFILLING);
    }

    @Test
    @DisplayName("格式错：拒，不建发货单")
    void rejectsBadFormat() {
        assertThatThrownBy(() -> handler.ship("ORD1", "STO", "123", null, Operator.merchant("M1")))
                .isInstanceOf(BizException.class);
        assertThat(write.saved).isNull();
    }

    @Test
    @DisplayName("重号（别单占用）：拒")
    void rejectsDuplicateOtherOrder() {
        OrdShipment other = new OrdShipment();
        other.setSubOrderNo("OTHER");
        other.setShipmentNo("SH9");
        read.dup = other;
        assertThatThrownBy(() -> handler.ship("ORD1", "STO", "773445089430765", null, Operator.merchant("M1")))
                .isInstanceOf(BizException.class);
        assertThat(write.saved).isNull();
    }

    @Test
    @DisplayName("同单重复填同号：幂等返回原发货单，不新建")
    void idempotentSameOrderSameWaybill() {
        OrdShipment mine = new OrdShipment();
        mine.setSubOrderNo("ORD1");
        mine.setShipmentNo("SH1");
        read.dup = mine;
        String no = handler.ship("ORD1", "STO", "773445089430765", null, Operator.merchant("M1"));
        assertThat(no).isEqualTo("SH1");
        assertThat(write.saved).isNull();
    }

    @Test
    @DisplayName("无可发货行（全已发）：拒")
    void rejectsWhenNothingToShip() {
        read.items.forEach(i -> i.setShipmentNo("SH0"));   // 全部已归属
        assertThatThrownBy(() -> handler.ship("ORD1", "STO", "773445089430765", null, Operator.merchant("M1")))
                .isInstanceOf(BizException.class);
    }
}
