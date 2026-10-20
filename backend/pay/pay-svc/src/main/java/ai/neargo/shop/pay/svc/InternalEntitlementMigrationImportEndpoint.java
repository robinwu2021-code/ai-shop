package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayMigrationRequests;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** Internal trusted operator protocol; the service requires the original durable core proof before writing. */
@RestController
public class InternalEntitlementMigrationImportEndpoint {
    private final LegacyCouponMigrationImportService imports;
    private final String token;
    public InternalEntitlementMigrationImportEndpoint(LegacyCouponMigrationImportService imports,@Value("${shop.services.internal-token:}") String token) {
        this.imports=imports;this.token=token;
    }
    @PostMapping(PayInternalPaths.MIGRATION_IMPORT)
    public ResponseEntity<?> importPlan(@RequestBody PayMigrationRequests.Import request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->imports.importPlan(request.scope(),request.command().planNo(),request.command().reason(),request.requestKey()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_IMPORT_GET)
    public ResponseEntity<?> get(@RequestBody PayMigrationRequests.PlanGet request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,()->imports.get(request.scope(),request.planNo()));
    }
}
