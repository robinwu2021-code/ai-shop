package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** No acknowledgement, no SENT transition; a lost response replays the same source event rather than creating one. */
public class CorePayEntitlementEventBridgePortImpl implements PayEntitlementEventBridgePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    private final ObjectMapper json;
    public CorePayEntitlementEventBridgePortImpl(ObjectProvider<CoreEntitlementInternalApi> api, ObjectMapper json) {
        this.api = api; this.json = json;
    }
    @Override public void publish(String eventNo, PayEntitlementEvent event) {
        if (!eventNo.equals(event.eventNo())) throw BizException.of(ErrorCode.CONFLICT);
        var envelope = PayEntitlementEventCodec.encode(event, json);
        var ack = EntitlementRemoteCalls.call(ServiceName.PLATFORM, () -> api.getObject().receiveEvent(envelope));
        if (!eventNo.equals(ack.eventNo()) || !ack.accepted()) throw BizException.of(ErrorCode.CONFLICT);
    }
}
