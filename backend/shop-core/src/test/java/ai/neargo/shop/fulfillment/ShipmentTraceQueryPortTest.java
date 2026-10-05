package ai.neargo.shop.fulfillment;

import ai.neargo.shop.fulfillment.entity.FulShipment;
import ai.neargo.shop.fulfillment.entity.FulShipmentTrace;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentTraceMapper;
import ai.neargo.shop.fulfillment.port.ShipmentTraceQueryPortImpl;
import ai.neargo.shop.spi.trade.ShipmentTraceQueryPort.CachedTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 订单详情读轨迹缓存（TDD-圆通物流直连 Y4）。 */
class ShipmentTraceQueryPortTest {

    private final ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
    private final ShipmentTraceMapper traceMapper = mock(ShipmentTraceMapper.class);
    private final ShipmentTraceQueryPortImpl port =
            new ShipmentTraceQueryPortImpl(shipmentMapper, traceMapper);

    private static FulShipmentTrace node(long at, String text, String loc) {
        FulShipmentTrace t = new FulShipmentTrace();
        t.setAt(at);
        t.setText(text);
        t.setLocation(loc);
        return t;
    }

    @Test
    void mapsShipmentAndTraceNewestFirst() {
        FulShipment s = new FulShipment();
        s.setShipmentNo("SHP-1");
        s.setSubOrderNo("SUB-1");
        s.setStatus(FulShipment.DELIVERED);
        when(shipmentMapper.selectOne(any())).thenReturn(s);
        // mapper 已按 at 倒序返回（orderByDesc）
        when(traceMapper.selectList(any())).thenReturn(List.of(
                node(2000L, "已签收", "杭州市"),
                node(1000L, "已揽收", "深圳市")));

        Optional<CachedTrace> r = port.traceOf("SUB-1");

        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(FulShipment.DELIVERED);
        assertThat(r.get().nodes()).hasSize(2);
        assertThat(r.get().nodes().get(0).text()).isEqualTo("已签收");
        assertThat(r.get().nodes().get(0).at()).isEqualTo(2000L);
        assertThat(r.get().nodes().get(1).location()).isEqualTo("深圳市");
    }

    @Test
    void noShipmentRow_isEmpty() {
        when(shipmentMapper.selectOne(any())).thenReturn(null);
        assertThat(port.traceOf("SUB-X")).isEmpty();
    }

    @Test
    void blankSubOrder_isEmpty() {
        assertThat(port.traceOf("")).isEmpty();
        assertThat(port.traceOf(null)).isEmpty();
    }
}
