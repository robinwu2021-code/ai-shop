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
        return traceOf(subOrderNo, null, null, null);
    }

    @Override
    public Optional<CachedTrace> traceOf(String subOrderNo, String surface,
                                         String buyerOpenid, String transId) {
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
                .map(t -> new CachedTrace.Node(t.getAt() == null ? 0L : t.getAt(), t.getText(),
                        t.getLocation(), t.getLatE6(), t.getLngE6()))
                .toList();
        if (surface == null) {
            return Optional.of(new CachedTrace(s.getStatus(), nodes));
        }
        String channel = channelFor(s, surface);
        return Optional.of(new CachedTrace(s.getStatus(), nodes, channel,
                "wx-plugin".equals(channel) ? s.getDisplayToken() : null, null, null, null));
    }

    /**
     * 这个端该用哪个展示渠道。**纯读库，不调外部接口** —— 外部调用（微信换 waybill_token）
     * 由 {@code WxWaybillBindJob} 预先做好落在列上，详情页只是把它读出来。
     *
     * <p>这是「缓存 30 分钟」真正落地的地方：读路径根本不触发任何外部请求，
     * 买家反复下拉刷新也只是多读几次库。
     *
     * <p>判据：库里备好的渠道在这个端可用就用它；否则一律 {@code self-map}（它三端都能呈现）。
     */
    @Override
    public java.util.Map<String, Long> signedAtOf(java.util.Collection<String> subOrderNos) {
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (FulShipment s : DataScopeContext.executeWithoutScope(() ->
                shipmentMapper.selectList(Wrappers.<FulShipment>lambdaQuery()
                        .select(FulShipment::getSubOrderNo, FulShipment::getSignedAt)
                        .in(FulShipment::getSubOrderNo, subOrderNos)
                        .isNotNull(FulShipment::getSignedAt)))) {
            if (s.getSignedAt() != null && s.getSignedAt() > 0) {
                // 一张子单理论上一条运单；真有多条取最晚签收的那条（整单才算收齐）
                out.merge(s.getSubOrderNo(), s.getSignedAt(), Math::max);
            }
        }
        return out;
    }

    private String channelFor(FulShipment s, String surface) {
        String saved = s.getDisplayChannel();
        boolean mp = "MP".equalsIgnoreCase(surface);
        if ("wx-plugin".equals(saved)) {
            // 微信插件只在小程序内能打开。B 端 App / 运营端拿到它也用不了，落回自建
            return mp && s.getDisplayToken() != null && !s.getDisplayToken().isBlank()
                    ? "wx-plugin" : "self-map";
        }
        return "self-map";
    }
}
