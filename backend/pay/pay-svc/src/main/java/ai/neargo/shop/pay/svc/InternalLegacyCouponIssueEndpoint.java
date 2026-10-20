package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssueService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalLegacyCouponIssueEndpoint {
    private final LegacyCouponIssueService service;private final String token;
    public InternalLegacyCouponIssueEndpoint(LegacyCouponIssueService service,@Value("${shop.services.internal-token:}") String token) {this.service=service;this.token=token;}
    @PostMapping(PayInternalPaths.LEGACY_ISSUE_CREATE)
    public ResponseEntity<?> issue(@RequestBody PayLegacyIssueRequests.Issue r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.issue(r.scope(),r.command(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.LEGACY_ISSUE_GET)
    public ResponseEntity<?> get(@RequestBody PayLegacyIssueRequests.Get r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.get(r.scope(),r.issueNo()));
    }
    @PostMapping(PayInternalPaths.LEGACY_ISSUE_RESUME)
    public ResponseEntity<?> resume(@RequestBody PayLegacyIssueRequests.Resume r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.resume(r.scope(),r.issueNo(),r.limit()));
    }
    @PostMapping(PayInternalPaths.LEGACY_ISSUE_LIST)
    public ResponseEntity<?> list(@RequestBody PayLegacyIssueRequests.History r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.list(r.scope(),r.sourceSystem(),r.sourceTemplateNo()));
    }
}
