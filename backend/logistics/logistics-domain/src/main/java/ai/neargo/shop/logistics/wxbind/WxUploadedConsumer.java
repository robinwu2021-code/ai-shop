package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.domain.WaybillSourceIdentity;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Uploaded means this exact package was sent, rather than every child sharing the payment. */
@Component
public class WxUploadedConsumer implements OutboxConsumer {
    static final String TYPE = "WX_SHIPPING_UPLOADED";
    private final WaybillMapper waybills;
    private final OutboxEventBus events;
    private final LogisticsProperties props;
    private final ObjectMapper json;
    private ShipmentSourcePort source;

    @Autowired(required = false)
    public void sourcePort(ShipmentSourcePort source) { this.source = source; }

    private boolean currentSource(LgsWaybill waybill) {
        return waybill.getSourceShipmentNo() == null || (source != null
                && source.identityOf(waybill.getBizRef())
                .map(identity -> WaybillSourceIdentity.matches(waybill, identity)).orElse(false));
    }


    public WxUploadedConsumer(WaybillMapper waybills, OutboxEventBus events, LogisticsProperties props,
                              ObjectMapper json) {
        this.waybills = waybills;
        this.events = events;
        this.props = props;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) { return TYPE.equals(eventType); }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        String outTradeNo = payload.path("outTradeNo").asString("");
        long at = payload.path("at").asLong(0);
        if (outTradeNo.isBlank() || at <= 0) { return; }
        JsonNode sent = payload.path("shipments");
        for (LgsWaybill waybill : waybills.selectList(Wrappers.<LgsWaybill>query()
                .eq("wx_out_trade_no", outTradeNo))) {
            if (!matches(waybill, sent) || !currentSource(waybill)) { continue; }
            if (waybill.getWxUploadedAt() == null) {
                int changed = waybills.update(null, WaybillSourceIdentity.unchanged(waybill)
                        .isNull("wx_uploaded_at").set("wx_uploaded_at", at));
                if (changed != 1) { continue; }
                waybill.setWxUploadedAt(at);
            }
            if (WxBindPolicy.ready(waybill, props.getWxBind().onShip())) {
                events.publish(new WxBindRequested(waybill.getShipmentNo()));
            }
        }
    }

    private static boolean matches(LgsWaybill waybill, JsonNode sent) {
        if (!sent.isArray() || sent.size() == 0) { return waybill.getSourceShipmentNo() == null; }
        for (JsonNode item : sent) {
            String source = item.path("sourceShipmentNo").asString(null);
            Long generation = item.path("sourceShipmentVersion").isNumber()
                    ? item.path("sourceShipmentVersion").asLong() : null;
            String carrier = waybill.getCarrierCorrectedFrom() == null
                    ? waybill.getCarrier() : waybill.getCarrierCorrectedFrom();
            if (Objects.equals(waybill.getBizRef(), item.path("subOrderNo").asString(null))
                    && Objects.equals(waybill.getSourceShipmentNo(), source)
                    && Objects.equals(waybill.getSourceShipmentVersion(), generation)
                    && Objects.equals(waybill.getWaybillNo(), item.path("trackingNo").asString(null))
                    && Objects.equals(carrier, item.path("expressCompany").asString(null))) { return true; }
        }
        return false;
    }
}
