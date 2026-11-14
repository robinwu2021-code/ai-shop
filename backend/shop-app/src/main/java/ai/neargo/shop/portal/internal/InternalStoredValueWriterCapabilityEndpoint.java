package ai.neargo.shop.portal.internal;

import ai.neargo.shop.pay.storedvalue.migration.StoredValueWriterCapabilityService;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Query;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Core can retain Pay writers even in remote routing mode; report its own process, never proxy another node. */
@RestController
public class InternalStoredValueWriterCapabilityEndpoint {
    private final StoredValueWriterCapabilityService writer;private final String token;
    public InternalStoredValueWriterCapabilityEndpoint(StoredValueWriterCapabilityService writer,@Value("${shop.services.internal-token:}") String token){this.writer=writer;this.token=token;}
    @PostMapping(PayInternalPaths.SV_MIGRATION_CAPABILITY)
    public ResponseEntity<?> capability(@RequestBody Query query,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->writer.capability(query));
    }
}
