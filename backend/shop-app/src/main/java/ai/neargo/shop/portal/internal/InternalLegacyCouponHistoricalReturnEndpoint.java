package ai.neargo.shop.portal.internal;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** Authenticated immutable source lookup and acknowledgement; cannot create a refund decision. */
@RestController
public class InternalLegacyCouponHistoricalReturnEndpoint {
    private final LegacyCouponHistoricalReturnSourcePort source;
    private final String token;
    public InternalLegacyCouponHistoricalReturnEndpoint(LegacyCouponHistoricalReturnSourcePort source,
            @Value("${shop.services.internal-token:}") String token) {this.source=source;this.token=token;}
    @PostMapping(EntitlementCoreInternal.HISTORY_RETURN_SOURCE)
    public ResponseEntity<?> source(@RequestBody EntitlementCoreInternal.HistoricalReturnQuery r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->source.resolve(r.context(),r.returnNo()));
    }
    @PostMapping(EntitlementCoreInternal.HISTORY_RETURN_ACK)
    public ResponseEntity<?> acknowledge(@RequestBody EntitlementCoreInternal.HistoricalReturnAck r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->{source.acknowledge(r.context(),r.receipt());return r.receipt();});
    }
}
