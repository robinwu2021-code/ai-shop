package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.migration.service.LegacyCouponTemplateManagementService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalLegacyCouponTemplateEndpoint {
    private final LegacyCouponTemplateManagementService service;
    private final String token;
    public InternalLegacyCouponTemplateEndpoint(LegacyCouponTemplateManagementService service,
            @Value("${shop.services.internal-token:}") String token) {this.service=service;this.token=token;}
    @PostMapping(PayInternalPaths.LEGACY_TEMPLATE_ARCHIVE)
    public ResponseEntity<?> archive(@RequestBody PayLegacyTemplateRequests.Archive r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.archive(r.scope(),r.reference(),r.archived(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.LEGACY_TEMPLATE_GET)
    public ResponseEntity<?> get(@RequestBody PayLegacyTemplateRequests.Get r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.get(r.scope(),r.reference()));
    }
    @PostMapping(PayInternalPaths.LEGACY_TEMPLATE_STATUS)
    public ResponseEntity<?> status(@RequestBody PayLegacyTemplateRequests.Status r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.status(r.scope(),r.reference(),r.status(),r.requestKey()));
    }
    @PostMapping(PayInternalPaths.LEGACY_TEMPLATE_SAVE)
    public ResponseEntity<?> save(@RequestBody PayLegacyTemplateRequests.Save r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->service.save(r.scope(),r.reference(),r.change(),r.requestKey()));
    }
}
