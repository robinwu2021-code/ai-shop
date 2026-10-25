package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.IndustryOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Token admission precedes original source authorization; no amount can be submitted by this transport. */
@RestController
public class InternalIndustryOrderEndpoint {
    private final IndustryOrderPort orders;
    private final String token;
    public InternalIndustryOrderEndpoint(IndustryOrderPort orders,
            @Value("${shop.services.internal-token:}") String token) {
        this.orders = orders; this.token = token;
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/accept-unpaid")
    public ResponseEntity<?> acceptUnpaid(@RequestBody IndustryOrderInternalApi.Get request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> orders.acceptUnpaid(request.identity(),request.orderNo()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/store/cancel")
    public ResponseEntity<?> cancelForStore(@RequestBody IndustryOrderInternalApi.StoreCancel request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> orders.cancelForStore(request.command(), request.key(), request.fulfillment()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/cancel")
    public ResponseEntity<?> cancel(@RequestBody IndustryOrderInternalApi.Place request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> orders.cancel(request.command(), request.key()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/store/get")
    public ResponseEntity<?> getForStore(@RequestBody IndustryOrderInternalApi.StoreGet request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> orders.getForStore(request.scope(), request.actorNo(), request.orderNo()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/store/dining-room/place")
    public ResponseEntity<?> placeForDiningRoom(@RequestBody IndustryOrderInternalApi.Place request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> orders.placeForDiningRoom(request.command(), request.key()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/store/place")
    public ResponseEntity<?> placeForStore(@RequestBody IndustryOrderInternalApi.Place request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> orders.placeForStore(request.command(), request.key()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/store/query")
    public ResponseEntity<?> queryForStore(@RequestBody IndustryOrderInternalApi.Query request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> orders.queryForStore(request.identity(), request.key()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/service/place")
    public ResponseEntity<?> placeForService(@RequestBody IndustryOrderInternalApi.ServicePlace request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> orders.placeForService(
                request.command(), request.key(), request.fulfillment(), request.staff()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/place")
    public ResponseEntity<?> place(@RequestBody IndustryOrderInternalApi.Place request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> orders.place(request.command(), request.key()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/query")
    public ResponseEntity<?> query(@RequestBody IndustryOrderInternalApi.Query request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> orders.query(request.identity(), request.key()));
    }
    @PostMapping(IndustryOrderInternalApi.ROOT + "/get")
    public ResponseEntity<?> get(@RequestBody IndustryOrderInternalApi.Get request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> orders.get(request.identity(), request.orderNo()));
    }
}
