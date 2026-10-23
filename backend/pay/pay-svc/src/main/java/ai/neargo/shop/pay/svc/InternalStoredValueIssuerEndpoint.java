package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Lookup;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalStoredValueIssuerEndpoint {
    private final StoredValueIssuerService issuers;
    private final String token;
    private final ai.neargo.shop.pay.storedvalue.issuer.StoredValueStoreIssuerService stores;
    public InternalStoredValueIssuerEndpoint(StoredValueIssuerService issuers,ai.neargo.shop.pay.storedvalue.issuer.StoredValueStoreIssuerService stores,@Value("${shop.services.internal-token:}") String token) {
        this.issuers=issuers;this.stores=stores;this.token=token;
    }
    @PostMapping(PayInternalPaths.SV_ISSUER_LOCATE)
    public ResponseEntity<?> locate(@RequestBody PayStoredValueIssuerRequests.Locate request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->issuers.locate(request.tenant(),request.issuer()));}
    @PostMapping(PayInternalPaths.SV_ISSUER_CHECK)
    public ResponseEntity<?> check(@RequestBody PayStoredValueIssuerRequests.Check request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->issuers.check(request.scope(),request.issuer(),request.config()));}
    @PostMapping(PayInternalPaths.SV_ISSUER_OPTIONS)
    public ResponseEntity<?> options(@RequestBody PayStoredValueIssuerRequests.Options request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->issuers.storeOptions(request.query()));}
    @PostMapping(PayInternalPaths.SV_ISSUER_REFRESH)
    public ResponseEntity<?> refresh(@RequestBody PayStoredValueIssuerRequests.PrepareStore request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->stores.refreshAck(request.scope()));}
    @PostMapping(PayInternalPaths.SV_ISSUER_PREPARE_STORE)
    public ResponseEntity<?> prepareStore(@RequestBody PayStoredValueIssuerRequests.PrepareStore request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->stores.prepare(request.scope()));
    }
    @PostMapping(PayInternalPaths.SV_ISSUER_FIND)
    public ResponseEntity<?> find(@RequestBody PayStoredValueIssuerRequests.Find request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->new Lookup(issuers.find(request.scope()).orElse(null)));
    }
    @PostMapping(PayInternalPaths.SV_ISSUER_CONFIGURE)
    public ResponseEntity<?> configure(@RequestBody PayStoredValueIssuerRequests.Configure request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,()->issuers.configure(request.scope(),request.command(),request.key()));
    }
}
