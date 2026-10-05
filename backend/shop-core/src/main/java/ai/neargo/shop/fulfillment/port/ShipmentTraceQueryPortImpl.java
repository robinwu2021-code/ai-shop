package ai.neargo.shop.fulfillment.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.fulfillment.entity.FulShipment;
import ai.neargo.shop.fulfillment.entity.FulShipmentTrace;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentTraceMapper;
import ai.neargo.shop.spi.trade.ShipmentTraceQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link ShipmentTraceQueryPort} 实现（Y4）。只读 {@code ful_shipment}/{@code ful_shipment_trace}。
 *
 * <p><b>绕数据域</b>：订单详情的调用方（C 端买家 / B 端店主）各有各的会话维度，而运单记录挂在
 * 子单上、轨迹挂在运单上 —— 按会话维度过滤会把买家自己的轨迹也过滤没了。作用域由详情端点自身
 * 的鉴权（买家只查得到自己的单、店主只查得到本店的单）保证，这一步只按 {@code subOrderNo} 点查。
 */
@Component
public class ShipmentTraceQueryPortImpl implements ShipmentTraceQueryPort {

    private final ShipmentMapper shipmentMapper;
    private final ShipmentTraceMapper traceMapper;

    public ShipmentTraceQueryPortImpl(ShipmentMapper shipmentMapper, ShipmentTraceMapper traceMapper) {
        this.shipmentMapper = shipmentMapper;
        this.traceMapper = traceMapper;
    }

    @Override
    public Optional<CachedTrace> traceOf(String subOrderNo) {
        if (subOrderNo == null || subOrderNo.isBlank()) {
            return Optional.empty();
        }
        FulShipment s = DataScopeContext.executeWithoutScope(() ->
                shipmentMapper.selectOne(Wrappers.<FulShipment>lambdaQuery()
                        .eq(FulShipment::getSubOrderNo, subOrderNo).last("limit 1")));
        if (s == null) {
            return Optional.empty();
        }
        List<CachedTrace.Node> nodes = DataScopeContext.executeWithoutScope(() ->
                traceMapper.selectList(Wrappers.<FulShipmentTrace>lambdaQuery()
                        .eq(FulShipmentTrace::getShipmentNo, s.getShipmentNo())
                        .orderByDesc(FulShipmentTrace::getAt)))   // 最新在前
                .stream()
                .map(t -> new CachedTrace.Node(t.getAt() == null ? 0L : t.getAt(), t.getText(), t.getLocation()))
                .toList();
        return Optional.of(new CachedTrace(s.getStatus(), nodes));
    }
}
