package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.logistics.WaybillWxUploadPort;
import ai.neargo.shop.spi.trade.WxShippingPort.ShippingItem;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 只为消化切换前已经落库、还没投出去的 {@code WX_SHIPPING_UPLOADED}。
 *
 * <p>新的上传成功由上传服务在同一事务里直接调 {@link WaybillWxUploadPort}，不再发这个事件；
 * 存量投完即可删掉本类。
 */
@Component
public class WxUploadedConsumer implements OutboxConsumer {
    static final String TYPE = "WX_SHIPPING_UPLOADED";
    private final WaybillWxUploadPort uploads;
    private final ObjectMapper json;

    public WxUploadedConsumer(WaybillWxUploadPort uploads, ObjectMapper json) {
        this.uploads = uploads;
        this.json = json;
    }

    @Override
    public String handlerId() { return "logistics-wx-uploaded"; }

    @Override
    public boolean supports(String eventType) { return TYPE.equals(eventType); }

    @Override
    public void consume(SysOutbox event) {
        JsonNode payload = json.readTree(event.getPayload());
        List<ShippingItem> sent = new ArrayList<>();
        JsonNode items = payload.path("shipments");
        if (items.isArray()) {
            items.forEach(item -> sent.add(json.treeToValue(item, ShippingItem.class)));
        }
        uploads.markUploaded(payload.path("outTradeNo").asString(""), payload.path("at").asLong(0), sent);
    }
}
