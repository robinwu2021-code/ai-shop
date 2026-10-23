package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationManifestService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Internal token plus migration-enabled guard. No financial mutation or cutover endpoint. */
@RestController
public class InternalStoredValueMigrationEndpoint {
    private final StoredValueMigrationManifestService migration;
    private final String token;
    public InternalStoredValueMigrationEndpoint(StoredValueMigrationManifestService migration,@Value("${shop.services.internal-token:}") String token) {
        this.migration=migration;this.token=token;
    }
    @PostMapping(PayInternalPaths.SV_MIGRATION_PROBE)
    public ResponseEntity<?> probe(@RequestBody PayStoredValueMigrationRequests.Probe request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->migration.probe(request.scopes()));
    }
    @PostMapping(PayInternalPaths.SV_MIGRATION_PREPARE)
    public ResponseEntity<?> prepare(@RequestBody PayStoredValueMigrationRequests.Prepare request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->migration.prepareTrusted(request.input(),request.key()));
    }
}
