package ai.neargo.shop.fulfillment;

import ai.neargo.shop.fulfillment.entity.FulShipment;
import ai.neargo.shop.fulfillment.entity.FulShipmentTrace;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.CarrierMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.FreightTemplateMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentTraceMapper;
import ai.neargo.shop.fulfillment.service.LogisticsService.TraceRefreshResult;
import ai.neargo.shop.fulfillment.service.impl.LogisticsServiceImpl;
import ai.neargo.shop.spi.logistics.LogisticsTracePort;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceResult.TraceNode;
import ai.neargo.shop.spi.logistics.TraceStatus;
import ai.neargo.shop.spi.trade.FulfillmentStatsPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轨迹轮询 {@code refreshInTransitTraces}（TDD-圆通物流直连 Y3）。
 *
 * <p>用假 provider 驱动（真查要圆通凭据）：验的是**拉回来之后**那段 ——
 * 节点追加去重、签收推进状态、缺 provider 不编造推进。
 */
class LogisticsTraceRefreshTest {

    private final ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
    private final ShipmentTraceMapper traceMapper = mock(ShipmentTraceMapper.class);
    private final FulfillmentStatsPort statsPort = mock(FulfillmentStatsPort.class);

    private LogisticsServiceImpl svc(LogisticsTracePort port) {
        return new LogisticsServiceImpl(shipmentMapper, traceMapper,
                mock(FreightTemplateMapper.class), mock(CarrierMapper.class),
                statsPort, port, new ObjectMapper());
    }

    private static FulShipment shipment(String no, String sub, String carrier, String waybill, String status) {
        FulShipment s = new FulShipment();
        s.setShipmentNo(no);
        s.setSubOrderNo(sub);
        s.setCarrier(carrier);
        s.setWaybillNo(waybill);
        s.setStatus(status);
        return s;
    }

    @Test
    void appendsNodesAndAdvancesStatus() {
        FulShipment a = shipment("SHP-A", "SUB-A", "YTO", "YT-A", FulShipment.CREATED);
        FulShipment b = shipment("SHP-B", "SUB-B", "YTO", "YT-B", FulShipment.CREATED);
        when(shipmentMapper.selectList(any())).thenReturn(List.of(a, b));
        when(traceMapper.selectList(any())).thenReturn(List.of());   // 库里还没有节点
        when(statsPort.storesOf(any())).thenReturn(Map.of("SUB-A", "ST-1"));   // B 没门店 → 默认路由

        LogisticsTracePort port = (store, carrier, waybill) -> switch (waybill) {
            case "YT-A" -> Optional.of(new TraceResult("YT-A", "YTO", TraceStatus.SIGNED, "yto", List.of(
                    new TraceNode(2000L, TraceStatus.SIGNED, "已签收", "杭州市"),
                    new TraceNode(1000L, TraceStatus.PICKED, "已揽收", "深圳市"))));
            case "YT-B" -> Optional.of(new TraceResult("YT-B", "YTO", TraceStatus.IN_TRANSIT, "yto", List.of(
                    new TraceNode(1500L, TraceStatus.IN_TRANSIT, "运输中", "武汉市"))));
            default -> Optional.empty();
        };

        TraceRefreshResult r = svc(port).refreshInTransitTraces(300);

        assertThat(r.scanned()).isEqualTo(2);
        assertThat(r.queried()).isEqualTo(2);
        assertThat(r.appended()).isEqualTo(2);        // 两单都有新节点
        assertThat(r.advanced()).isEqualTo(2);        // A: CREATED→DELIVERED，B: CREATED→IN_TRANSIT
        assertThat(r.delivered()).isEqualTo(1);       // 只有 A 签收
        assertThat(a.getStatus()).isEqualTo(FulShipment.DELIVERED);
        assertThat(b.getStatus()).isEqualTo(FulShipment.IN_TRANSIT);
        verify(traceMapper, times(3)).insert(any(FulShipmentTrace.class));  // 2 + 1 条节点
        verify(shipmentMapper, times(2)).updateById(any(FulShipment.class));
    }

    /** 顺丰、中通在快递100 查询时要校验手机号：轮询要把子单上的收件人手机号带下去（TDD-快递100轨迹查询 AC2） */
    @Test
    void receiverPhoneReachesTracePort() {
        FulShipment a = shipment("SHP-A", "SUB-A", "SF", "SF-A", FulShipment.CREATED);
        FulShipment b = shipment("SHP-B", "SUB-B", "YTO", "YT-B", FulShipment.CREATED);
        when(shipmentMapper.selectList(any())).thenReturn(List.of(a, b));
        when(traceMapper.selectList(any())).thenReturn(List.of());
        when(statsPort.storesOf(any())).thenReturn(Map.of());
        when(statsPort.receiverPhonesOf(any())).thenReturn(Map.of("SUB-A", "13800138000"));   // B 没手机号

        Map<String, String> seen = new java.util.HashMap<>();
        LogisticsTracePort port = new LogisticsTracePort() {
            @Override
            public Optional<TraceResult> trace(String store, String carrier, String waybill) {
                return trace(store, carrier, waybill, null);
            }

            @Override
            public Optional<TraceResult> trace(String store, String carrier, String waybill, String phone) {
                seen.put(waybill, phone == null ? "<null>" : phone);
                return Optional.empty();
            }
        };
        svc(port).refreshInTransitTraces(300);
        assertThat(seen).containsEntry("SF-A", "13800138000").containsEntry("YT-B", "<null>");
    }

    @Test
    void dedupExistingNodes() {
        FulShipment a = shipment("SHP-A", "SUB-A", "YTO", "YT-A", FulShipment.IN_TRANSIT);
        when(shipmentMapper.selectList(any())).thenReturn(List.of(a));
        when(statsPort.storesOf(any())).thenReturn(Map.of("SUB-A", "ST-1"));
        // 库里已有「1000|已揽收」这条
        FulShipmentTrace existing = new FulShipmentTrace();
        existing.setShipmentNo("SHP-A");
        existing.setAt(1000L);
        existing.setText("已揽收");
        when(traceMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));

        LogisticsTracePort port = (store, carrier, waybill) ->
                Optional.of(new TraceResult("YT-A", "YTO", TraceStatus.SIGNED, "yto", List.of(
                        new TraceNode(2000L, TraceStatus.SIGNED, "已签收", "杭州市"),
                        new TraceNode(1000L, TraceStatus.PICKED, "已揽收", "深圳市")))); // 这条已存在

        TraceRefreshResult r = svc(port).refreshInTransitTraces(300);

        assertThat(r.appended()).isEqualTo(1);        // 只新增「已签收」，「已揽收」去重
        verify(traceMapper, times(1)).insert(any(FulShipmentTrace.class));
    }

    @Test
    void noProviderNoWrite() {
        FulShipment a = shipment("SHP-A", "SUB-A", "YTO", "YT-A", FulShipment.CREATED);
        when(shipmentMapper.selectList(any())).thenReturn(List.of(a));
        when(statsPort.storesOf(any())).thenReturn(Map.of());

        // 缺凭据：路由回落空（available()=false）
        LogisticsTracePort port = (store, carrier, waybill) -> Optional.empty();

        TraceRefreshResult r = svc(port).refreshInTransitTraces(300);

        assertThat(r.scanned()).isEqualTo(1);
        assertThat(r.queried()).isZero();
        assertThat(r.advanced()).isZero();
        assertThat(a.getStatus()).isEqualTo(FulShipment.CREATED);   // 不编造推进
        verify(shipmentMapper, never()).updateById(any(FulShipment.class));
        verify(traceMapper, never()).insert(any(FulShipmentTrace.class));
    }

    @Test
    void unknownStatusKeepsCurrent() {
        FulShipment a = shipment("SHP-A", "SUB-A", "YTO", "YT-A", FulShipment.IN_TRANSIT);
        when(shipmentMapper.selectList(any())).thenReturn(List.of(a));
        when(traceMapper.selectList(any())).thenReturn(List.of());
        when(statsPort.storesOf(any())).thenReturn(Map.of("SUB-A", "ST-1"));

        LogisticsTracePort port = (store, carrier, waybill) ->
                Optional.of(new TraceResult("YT-A", "YTO", TraceStatus.UNKNOWN, "yto", List.of()));

        TraceRefreshResult r = svc(port).refreshInTransitTraces(300);

        assertThat(r.queried()).isEqualTo(1);
        assertThat(r.advanced()).isZero();
        assertThat(a.getStatus()).isEqualTo(FulShipment.IN_TRANSIT);   // UNKNOWN 不动当前状态
    }
}
