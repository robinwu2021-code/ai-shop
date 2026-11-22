package ai.neargo.shop.invoice.channel.stub;

import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 本地桩通道：收下即算送达，不出网。正式加值中心签约前，主体配置 {@code gateway_code=STUB}；
 * 报文全量留在 {@code ivc_transmission.payload}，换成正式通道后对这些记录补传。
 */
@Component
public class StubInvoiceGateway implements InvoiceGateway {

    public String code() {
        return "STUB";
    }

    public GatewayReceipt send(GatewayRequest request) {
        String ref = "STUB-" + UUID.nameUUIDFromBytes(request.payloadJson().getBytes(StandardCharsets.UTF_8));
        return GatewayReceipt.accepted(ref);
    }
}
