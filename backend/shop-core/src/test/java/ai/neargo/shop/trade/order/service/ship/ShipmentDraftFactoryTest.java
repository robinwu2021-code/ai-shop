package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentDraftFactoryTest {

    @Test
    @DisplayName("建快递发货单：字段齐，状态 CREATED")
    void buildsExpress() {
        OrdShipment s = ShipmentDraftFactory.express("SH1", "ORD1", "ST-A", "STO", "773445089430765");
        assertThat(s.getShipmentNo()).isEqualTo("SH1");
        assertThat(s.getSubOrderNo()).isEqualTo("ORD1");
        assertThat(s.getShipStoreNo()).isEqualTo("ST-A");
        assertThat(s.getFulfillment()).isEqualTo("EXPRESS");
        assertThat(s.getCarrier()).isEqualTo("STO");
        assertThat(s.getWaybillNo()).isEqualTo("773445089430765");
        assertThat(s.getStatus()).isEqualTo(OrdShipment.CREATED);
    }

    @Test
    @DisplayName("绑行：记发货单归属 + 整件发出")
    void bindsItem() {
        OrdItem i = new OrdItem();
        i.setQty(3);
        ShipmentDraftFactory.bind(i, "SH1");
        assertThat(i.getShipmentNo()).isEqualTo("SH1");
        assertThat(i.getShippedQty()).isEqualTo(3);
    }
}
