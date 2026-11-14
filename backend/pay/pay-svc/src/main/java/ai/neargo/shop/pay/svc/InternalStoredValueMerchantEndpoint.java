package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.client.PayStoredValueMerchantRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValueMerchantQueryService;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalStoredValueMerchantEndpoint {
    private final StoredValueMerchantQueryService reads;
    private final String token;
    public InternalStoredValueMerchantEndpoint(StoredValueMerchantQueryService reads,
            @Value("${shop.services.internal-token:}") String token){this.reads=reads;this.token=token;}
    @PostMapping(PayInternalPaths.SV_MERCHANT_DIRECTORY)
    public ResponseEntity<?> directory(@RequestBody PayStoredValueMerchantRequests.Directory r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->reads.directory(r.scope(),r.storeNo(),r.users(),r.keywordHash(),r.cursor(),r.size()));
    }
    @PostMapping(PayInternalPaths.SV_MERCHANT_ACCOUNT)
    public ResponseEntity<?> account(@RequestBody PayStoredValueMerchantRequests.Account r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->reads.account(r.scope(),r.storeNo(),r.accountRef()));
    }
    @PostMapping(PayInternalPaths.SV_MERCHANT_RECORDS)
    public ResponseEntity<?> records(@RequestBody PayStoredValueMerchantRequests.Records r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->reads.records(r.scope(),r.storeNo(),r.type(),r.from(),r.to(),r.accountRef(),r.cursor(),r.size()));
    }
    @PostMapping(PayInternalPaths.SV_MERCHANT_RECORD)
    public ResponseEntity<?> record(@RequestBody PayStoredValueMerchantRequests.Record r,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given){
        return InternalServiceResponses.invoke(token,given,()->reads.record(r.scope(),r.storeNo(),r.recordNo()));
    }
}
