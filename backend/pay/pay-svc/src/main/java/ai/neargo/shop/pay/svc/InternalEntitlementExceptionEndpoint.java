package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayExceptionRequests;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionRecoveryService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class InternalEntitlementExceptionEndpoint {
    private final EntitlementExceptionService exceptions;
    private final EntitlementExceptionRecoveryService recovery;
    private final String token;
    public InternalEntitlementExceptionEndpoint(EntitlementExceptionService exceptions,
            EntitlementExceptionRecoveryService recovery,@Value("${shop.services.internal-token:}") String token) {
        this.exceptions=exceptions; this.recovery=recovery; this.token=token;
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_RETRY_EVENT)
    public ResponseEntity<?> retryEvent(@RequestBody PayExceptionRequests.RetryGrant r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> recovery.retryEvent(r.scope(),r.exceptionNo(),r.command(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_DEAD_LETTERS)
    public ResponseEntity<?> deadLetters(@RequestBody PayExceptionRequests.DeadLetters r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> recovery.deadLetters(r.scope(),r.cursor(),r.limit()));
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_LIST)
    public ResponseEntity<?> list(@RequestBody PayExceptionRequests.ListExceptions r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> exceptions.list(r.scope(),r.kind(),r.status(),r.page(),r.size()));
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_GET)
    public ResponseEntity<?> get(@RequestBody PayExceptionRequests.GetException r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> exceptions.get(r.scope(),r.exceptionNo()));
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_RETRY)
    public ResponseEntity<?> retry(@RequestBody PayExceptionRequests.RetryGrant r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> recovery.retryGrant(r.scope(),r.exceptionNo(),r.command(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_SOURCE)
    public ResponseEntity<?> source(@RequestBody PayExceptionRequests.RetryGrant r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> recovery.querySource(r.scope(),r.exceptionNo(),r.command(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_ACTIONS)
    public ResponseEntity<?> actions(@RequestBody PayExceptionRequests.Actions r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> exceptions.actions(r.scope(),r.exceptionNo(),
                r.page(),r.size()));
    }
    @PostMapping(PayInternalPaths.EXCEPTIONS_LIST_ACROSS)
    public ResponseEntity<?> listAcross(@RequestBody PayExceptionRequests.ListAcross request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String supplied) {
        return InternalServiceResponses.invoke(token, supplied, () -> exceptions.listAcross(request.scope(),
                request.kind(), request.status(), request.page(), request.size()));
    }
}
