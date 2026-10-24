package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayStoredValueConsumerRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValueConsumerQueryService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Money histories and notification facts share service authentication, never merchant impersonation. */
@RestController
public class InternalStoredValueConsumerEndpoint {
    private final StoredValueConsumerQueryService reads;
    private final String token;
    public InternalStoredValueConsumerEndpoint(StoredValueConsumerQueryService reads,
            @Value("${shop.services.internal-token:}") String token) { this.reads=reads; this.token=token; }
    @PostMapping(PayInternalPaths.SV_CONSUMER_STORE)
    public ResponseEntity<?> store(@RequestBody PayStoredValueConsumerRequests.Store r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->reads.store(r.context(),r.storeNo()));
    }
    @PostMapping(PayInternalPaths.SV_CONSUMER_RECORDS)
    public ResponseEntity<?> records(@RequestBody PayStoredValueConsumerRequests.Records r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->reads.records(r.context(),r.storeNo(),r.type(),
                r.from(),r.to(),r.focusDocumentNo(),r.cursor(),r.size()));
    }
    @PostMapping(PayInternalPaths.SV_NOTIFICATION_FACT)
    public ResponseEntity<?> fact(@RequestBody PayStoredValueConsumerRequests.Fact r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->reads.notificationFact(r.context(),r.usageNo()));
    }
    @PostMapping(PayInternalPaths.SV_CONSUMER_ISSUERS)
    public ResponseEntity<?> issuers(@RequestBody PayStoredValueConsumerRequests.Issuers r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->reads.issuers(r.context(),r.cursor(),r.size()));}
    @PostMapping(PayInternalPaths.SV_CONSUMER_ISSUER)
    public ResponseEntity<?> issuer(@RequestBody PayStoredValueConsumerRequests.Issuer r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->reads.issuer(r.context(),r.issuerRef()));}
    @PostMapping(PayInternalPaths.SV_CONSUMER_LOCATE_ISSUER)
    public ResponseEntity<?> locateIssuer(@RequestBody PayStoredValueConsumerRequests.Locate r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->reads.locateIssuer(r.context(),r.documentNo()));}
    @PostMapping(PayInternalPaths.SV_CONSUMER_ISSUER_RECORDS)
    public ResponseEntity<?> issuerRecords(@RequestBody PayStoredValueConsumerRequests.IssuerRecords r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->reads.issuerRecords(r.context(),r.issuerRef(),r.type(),r.from(),r.to(),r.focus(),r.cursor(),r.size()));}
}
