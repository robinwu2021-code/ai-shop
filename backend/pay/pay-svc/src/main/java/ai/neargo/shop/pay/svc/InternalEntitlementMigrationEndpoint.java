package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayMigrationRequests;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Service-authenticated evidence tooling; no import, activation, or ownership-transfer operation. */
@RestController
public class InternalEntitlementMigrationEndpoint {
    private final EntitlementMigrationService migration;
    private final String token;
    public InternalEntitlementMigrationEndpoint(EntitlementMigrationService migration,@Value("${shop.services.internal-token:}") String token) {
        this.migration=migration; this.token=token;
    }
    @PostMapping(PayInternalPaths.MIGRATION_STAGE)
    public ResponseEntity<?> stage(@RequestBody PayMigrationRequests.Stage request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,
                () -> migration.stage(request.scope(),request.command(),request.requestKey()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_STAGE_GET)
    public ResponseEntity<?> get(@RequestBody PayMigrationRequests.Get request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,() -> migration.get(request.scope(),request.stageNo()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_STAGE_VERIFY)
    public ResponseEntity<?> verify(@RequestBody PayMigrationRequests.Get request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,() -> migration.verify(request.scope(),request.stageNo()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_STAGE_MANIFEST)
    public ResponseEntity<?> manifest(@RequestBody PayMigrationRequests.Stage request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,
                () -> migration.stageManifest(request.scope(),request.command(),request.requestKey()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_STAGE_ASSESS)
    public ResponseEntity<?> assess(@RequestBody PayMigrationRequests.Get request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,() -> migration.assess(request.scope(),request.stageNo()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_ALIAS_COMPARE)
    public ResponseEntity<?> compareAlias(@RequestBody PayMigrationRequests.AliasPair request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,() -> migration.compareAlias(request.scope(),request.pair()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_ALIAS_RECOGNIZE)
    public ResponseEntity<?> recognizeAlias(@RequestBody PayMigrationRequests.AliasRecognition request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,
                () -> migration.recognizeAlias(request.scope(),request.command(),request.requestKey()));
    }
    @PostMapping(PayInternalPaths.MIGRATION_ALIAS_GET)
    public ResponseEntity<?> getAlias(@RequestBody PayMigrationRequests.AliasGet request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied) {
        return InternalServiceResponses.invoke(token,supplied,() -> migration.getAlias(request.scope(),request.aliasNo()));
    }
}
