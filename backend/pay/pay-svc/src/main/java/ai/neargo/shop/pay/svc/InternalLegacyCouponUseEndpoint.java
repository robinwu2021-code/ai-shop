package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayLegacyCouponRequests;
import ai.neargo.shop.pay.migration.service.LegacyCouponUseService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalLegacyCouponUseEndpoint {
    private final LegacyCouponUseService service;
    private final String token;
    public InternalLegacyCouponUseEndpoint(LegacyCouponUseService service,@Value("${shop.services.internal-token:}") String token) {
        this.service=service;this.token=token;
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_SNAPSHOT)
    public ResponseEntity<?> snapshot(@RequestBody PayLegacyCouponRequests.SnapshotRequest r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.snapshot(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_ESTIMATE)
    public ResponseEntity<?> estimate(@RequestBody PayLegacyCouponRequests.EstimateRequest r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.estimate(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_QUOTE)
    public ResponseEntity<?> quote(@RequestBody PayLegacyCouponRequests.PrepareRequest r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.quote(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_PREPARE)
    public ResponseEntity<?> prepare(@RequestBody PayLegacyCouponRequests.PrepareRequest r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.prepare(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_COMMIT)
    public ResponseEntity<?> commit(@RequestBody PayLegacyCouponRequests.CompleteRequest r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.commit(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_RELEASE)
    public ResponseEntity<?> release(@RequestBody PayLegacyCouponRequests.CompleteRequest r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.release(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_REVERSE)
    public ResponseEntity<?> reverse(@RequestBody PayLegacyCouponRequests.CompleteRequest r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.reverse(r.context(),r.command()));
    }
    @PostMapping(PayInternalPaths.LEGACY_COUPON_QUERY)
    public ResponseEntity<?> query(@RequestBody PayLegacyCouponRequests.Query r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.query(r.context(),r.entityNo(),r.operationNo()));
    }
}
