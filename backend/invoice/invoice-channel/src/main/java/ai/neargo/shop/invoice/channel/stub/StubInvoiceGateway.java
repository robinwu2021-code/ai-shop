package ai.neargo.shop.invoice.channel.stub;

import ai.neargo.shop.invoice.channel.base.AbstractInvoiceGateway;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 本地桩：收下即算送达，不出网，任何税制都收。正式供应商签约前，主体配置 {@code gateway_code=STUB}；
 * 报文全量留在 {@code ivc_transmission.payload}，换成正式供应商后对这些记录补传。
 */
@Component
public class StubInvoiceGateway extends AbstractInvoiceGateway {

    static final ProviderProfile PROFILE = new ProviderProfile(Set.of(ProviderProfile.ANY_REGIME),
            NumberingMode.LOCAL_RANGE, ReportMode.ASYNC_UPLOAD, false);

    public StubInvoiceGateway(ObjectMapper json) {
        super(json);
    }

    @Override
    public String code() {
        return "STUB";
    }

    @Override
    public ProviderProfile profile() {
        return PROFILE;
    }

    @Override
    protected GatewayReceipt upload(GatewayRequest request, JsonNode message) {
        String ref = "STUB-" + UUID.nameUUIDFromBytes(request.payloadJson().getBytes(StandardCharsets.UTF_8));
        return GatewayReceipt.accepted(ref);
    }
}
