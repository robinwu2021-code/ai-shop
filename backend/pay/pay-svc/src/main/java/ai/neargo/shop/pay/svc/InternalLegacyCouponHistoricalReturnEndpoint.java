package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalLegacyCouponHistoricalReturnEndpoint {
    private final LegacyCouponHistoricalReturnService service;
    private final String token;
    public InternalLegacyCouponHistoricalReturnEndpoint(LegacyCouponHistoricalReturnService service,
            @Value("${shop.services.internal-token:}") String token) {this.service=service;this.token=token;}
    @PostMapping(PayInternalPaths.HISTORY_RETURN_REVERSE)
    public ResponseEntity<?> reverse(@RequestBody PayHistoricalReturnRequests.Query r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.reverse(r.context(),r.entityNo(),r.returnNo()));
    }
    @PostMapping(PayInternalPaths.HISTORY_RETURN_QUERY)
    public ResponseEntity<?> query(@RequestBody PayHistoricalReturnRequests.Query r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.query(r.context(),r.entityNo(),r.returnNo()));
    }
}
