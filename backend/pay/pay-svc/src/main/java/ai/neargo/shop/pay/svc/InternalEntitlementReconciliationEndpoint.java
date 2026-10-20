package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayReconciliationRequests;
import ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Token validation precedes pay access, keeping standalone and embedded comparison semantics identical. */
@RestController
public class InternalEntitlementReconciliationEndpoint {
    private final EntitlementReconciliationService pay;
    private final String token;
    public InternalEntitlementReconciliationEndpoint(EntitlementReconciliationService pay,
            @Value("${shop.services.internal-token:}") String token) { this.pay=pay; this.token=token; }
    @PostMapping(PayInternalPaths.RECON_CHECK)
    public ResponseEntity<?> check(@RequestBody PayReconciliationRequests.Start r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> pay.check(r.scope(),r.command(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.RECON_LIST)
    public ResponseEntity<?> list(@RequestBody PayReconciliationRequests.ListRuns r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> pay.list(r.scope(),r.bizDate(),r.page(),r.size()));
    }
    @PostMapping(PayInternalPaths.RECON_GET)
    public ResponseEntity<?> get(@RequestBody PayReconciliationRequests.GetRun r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> pay.get(r.scope(),r.reconNo()));
    }
    @PostMapping(PayInternalPaths.RECON_DIFFS)
    public ResponseEntity<?> differences(@RequestBody PayReconciliationRequests.Differences r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,() -> pay.differences(r.scope(),r.reconNo(),r.page(),r.size()));
    }
}
