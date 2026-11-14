package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.service.StoredValueOrganizationImpactService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestController
public class InternalStoredValueOrganizationEndpoint {
 private final StoredValueOrganizationImpactService reads;private final String token;
 public InternalStoredValueOrganizationEndpoint(StoredValueOrganizationImpactService reads,@Value("${shop.services.internal-token:}") String token){this.reads=reads;this.token=token;}
 @PostMapping(PayInternalPaths.SV_ORGANIZATION_IMPACT)
 public ResponseEntity<?> inspect(@RequestBody PayStoredValueMerchantRequests.OrganizationImpact request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String supplied){return InternalServiceResponses.invoke(token,supplied,()->reads.inspect(request.tenantNo(),request.entityNo(),request.storeNo()));}
}
