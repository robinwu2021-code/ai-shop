package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayLifecycleRequests;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ExpireResult;
import ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The owner checks scope and asset revision after transport authentication. */
@RestController
public class InternalEntitlementLifecycleEndpoint {
    private final EntitlementLifecycleService pay;
    private final String token;
    public InternalEntitlementLifecycleEndpoint(EntitlementLifecycleService pay,
            @Value("${shop.services.internal-token:}") String token) { this.pay=pay; this.token=token; }
    @PostMapping(PayInternalPaths.LIFECYCLE_FREEZE)
    public ResponseEntity<?> freeze(@RequestBody PayLifecycleRequests.Freeze r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> pay.freeze(r.scope(),r.instanceNo(),
                r.command(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.LIFECYCLE_STATE)
    public ResponseEntity<?> state(@RequestBody PayLifecycleRequests.State r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> pay.state(r.scope(),r.instanceNo()));
    }
    @PostMapping(PayInternalPaths.LIFECYCLE_EXPIRE)
    public ResponseEntity<?> expire(@RequestBody PayLifecycleRequests.Expire r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> new ExpireResult(pay.expireDue(r.context(),r.limit())));
    }
    @PostMapping(PayInternalPaths.LIFECYCLE_REMINDERS)
    public ResponseEntity<?> expiringSoon(@RequestBody PayLifecycleRequests.Reminders request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied) {
        return InternalServiceResponses.invoke(token, supplied,
                () -> pay.expiringSoon(request.context(), request.afterId(), request.limit()));
    }
}
