package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PaySaleRequests;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class InternalSaleReservationEndpoint {
    private final EntitlementSaleReservationPort sales;
    private final String token;
    public InternalSaleReservationEndpoint(EntitlementSaleReservationPort sales,
            @Value("${shop.services.internal-token:}") String token) { this.sales=sales; this.token=token; }
    @PostMapping(PayInternalPaths.SALE_RESERVE)
    public ResponseEntity<?> reserve(@RequestBody PaySaleRequests.Reserve r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token,given, () -> sales.reserve(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.SALE_RELEASE)
    public ResponseEntity<?> release(@RequestBody PaySaleRequests.Release r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token,given, () -> sales.release(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.SALE_QUERY)
    public ResponseEntity<?> query(@RequestBody PaySaleRequests.Query r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token,given, () -> sales.query(r.context(),r.entityNo(),r.reservationNo()));
    }
}
