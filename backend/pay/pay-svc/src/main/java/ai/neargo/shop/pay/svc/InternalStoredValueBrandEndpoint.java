package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Prepare;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalStoredValueBrandEndpoint {
    private final StoredValueBrandRuntimeReadiness readiness;private final StoredValueBrandActivationService service;private final String token;
    public InternalStoredValueBrandEndpoint(StoredValueBrandRuntimeReadiness readiness,StoredValueBrandActivationService service,@Value("${shop.services.internal-token:}")String token){this.readiness=readiness;this.service=service;this.token=token;}
    @PostMapping(PayInternalPaths.SV_BRAND_CAPABILITY)
    public ResponseEntity<?> capability(@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,readiness::capability);}
    @PostMapping(PayInternalPaths.SV_BRAND_ACTIVATE)
    public ResponseEntity<?> activate(@RequestBody Prepare request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.activate(request));}
}
