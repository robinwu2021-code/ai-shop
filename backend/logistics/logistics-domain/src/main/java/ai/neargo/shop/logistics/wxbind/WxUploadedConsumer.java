package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 交易域报告「微信发货信息已上传」→ 记在运单上，并判一次换 token（另一半前置是已揽收）。
 *
 * <p>按商户单号找运单（登记快照里存了同一个值），物流不必认识「哪几张子单属于哪个支付单」。
 * 这条事件可能先于登记到达 —— 那时运单上还没有商户单号、这里一张都匹配不到；所以登记快照里也带一份上传时间。
 */
@Component
public class WxUploadedConsumer implements OutboxConsumer {

    static final String TYPE = "WX_SHIPPING_UPLOADED";

    private final WaybillMapper waybills;
    private final OutboxEventBus events;
    private final LogisticsProperties props;
    private final ObjectMapper json;

    public WxUploadedConsumer(WaybillMapper waybills, OutboxEventBus events, LogisticsProperties props,
                              ObjectMapper json) {
        this.waybills = waybills;
        this.events = events;
        this.props = props;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return TYPE.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        String outTradeNo = p.path("outTradeNo").asString("");
        long at = p.path("at").asLong(System.currentTimeMillis());
        if (outTradeNo.isBlank()) {
            return;
        }
        for (LgsWaybill w : waybills.selectList(Wrappers.<LgsWaybill>query().eq("wx_out_trade_no", outTradeNo))) {
            if (w.getWxUploadedAt() == null) {
                LgsWaybill patch = LgsWaybill.patch(w.getId());
                patch.setWxUploadedAt(at);
                waybills.updateById(patch);
                w.setWxUploadedAt(at);
            }
            if (WxBindPolicy.ready(w, props.getWxBind().onShip())) {
                events.publish(new WxBindRequested(w.getShipmentNo()));
            }
        }
    }
}
