package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationManifestService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Internal token plus explicit maintenance execution gate. No balance mutation, DDL or activation endpoint. */
@RestController
public class InternalStoredValueMigrationEndpoint {
    private final StoredValueMigrationManifestService migration;
    private final String token;
    private final ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationOperator operator;
    private final ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationExecutionService execution;
    private final ai.neargo.shop.pay.storedvalue.migration.StoredValueWriterCapabilityService writer;
    public InternalStoredValueMigrationEndpoint(StoredValueMigrationManifestService migration,ai.neargo.shop.pay.storedvalue.migration.StoredValueWriterCapabilityService writer,ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationExecutionService execution,ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationOperator operator,@Value("${shop.services.internal-token:}") String token) {
        this.operator=operator;this.migration=migration;this.token=token;this.writer=writer;this.execution=execution;
    }


    @PostMapping(PayInternalPaths.SV_MIGRATION_SOURCES)
    public ResponseEntity<?> sources(@RequestBody PayStoredValueMigrationRequests.Operator request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->operator.sources(request.operator(),request.approval(),request.proof()));}
    @PostMapping(PayInternalPaths.SV_MIGRATION_OPERATOR)
    public ResponseEntity<?> operator(@RequestBody PayStoredValueMigrationRequests.Operator request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->operator.execute(request.action(),request.operator(),request.approval(),request.proof(),request.expectedApprovalHash()));}
    @PostMapping(PayInternalPaths.SV_MIGRATION_COMMAND)
    public ResponseEntity<?> command(@RequestBody PayStoredValueMigrationRequests.Operator request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->operator.command(request.operator(),request.approval(),request.commandNo(),request.proof()));}
    @PostMapping(PayInternalPaths.SV_MIGRATION_CAPABILITY)
    public ResponseEntity<?> capability(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Query query,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->writer.capability(query));
    }
    @PostMapping(PayInternalPaths.SV_MIGRATION_PAUSE)
    public ResponseEntity<?> pause(@RequestBody PayStoredValueMigrationRequests.Pause request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->execution.pause(request.input(),request.manifestNo(),request.key(),request.proof()));
    }
    @PostMapping(PayInternalPaths.SV_MIGRATION_APPLY)
    public ResponseEntity<?> apply(@RequestBody PayStoredValueMigrationRequests.Apply request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->execution.apply(request.input(),request.manifestNo(),request.pauseReceiptNo(),request.key(),request.proof()));
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
