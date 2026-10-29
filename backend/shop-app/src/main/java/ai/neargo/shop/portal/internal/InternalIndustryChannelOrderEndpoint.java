package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.IndustryChannelOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelOrderInternalApi.Request;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Existing internal token admission precedes original source, scope, policy and inventory checks.
 */
@RestController
public final class InternalIndustryChannelOrderEndpoint {
    private final IndustryChannelOrderPort orders;
    private final String token;

    public InternalIndustryChannelOrderEndpoint(
            IndustryChannelOrderPort orders,
            @Value("${shop.services.internal-token:}") String token) {
        this.orders = orders;
        this.token = token;
    }

    @PostMapping(IndustryChannelOrderInternalApi.ROOT + "/prepare")
    public ResponseEntity<?> prepare(
            @RequestBody Request request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> orders.prepare(request.reference()));
    }

    @PostMapping(IndustryChannelOrderInternalApi.ROOT + "/get")
    public ResponseEntity<?> get(
            @RequestBody Request request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> orders.get(request.reference()));
    }

    @PostMapping(IndustryChannelOrderInternalApi.ROOT + "/confirm")
    public ResponseEntity<?> confirm(
            @RequestBody Request request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> orders.confirm(request.reference()));
    }

    @PostMapping(IndustryChannelOrderInternalApi.ROOT + "/cancel")
    public ResponseEntity<?> cancel(
            @RequestBody Request request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> orders.cancel(request.reference()));
    }
}
