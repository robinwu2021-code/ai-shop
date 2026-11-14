package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.policy.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalStoredValuePolicyEndpoint {
 @org.springframework.beans.factory.annotation.Autowired private StoredValueGovernanceCapabilityService readiness;
 private final StoredValueIssuePolicyService query; private final StoredValuePolicyConfigurationService configuration; private final String token;
 public InternalStoredValuePolicyEndpoint(StoredValueIssuePolicyService query,StoredValuePolicyConfigurationService configuration,@Value("${shop.services.internal-token:}")String token){this.query=query;this.configuration=configuration;this.token=token;}
 @PostMapping(PayInternalPaths.SV_POLICY_LOCATE)
 public ResponseEntity<?> locate(@RequestBody PayStoredValuePolicyRequests.Locate r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->query.locate(r.tenantNo(),r.policyNo()));}
 @PostMapping(PayInternalPaths.SV_POLICY_LIST)
 public ResponseEntity<?> list(@RequestBody CatalogScope r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->query.list(r));}
 @PostMapping(PayInternalPaths.SV_POLICY_EVALUATE)
 public ResponseEntity<?> evaluate(@RequestBody StoreScope r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->query.evaluate(r));}
 @PostMapping(PayInternalPaths.SV_POLICY_CONFIGURE)
 public ResponseEntity<?> configure(@RequestBody PayStoredValuePolicyRequests.Configure r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->configuration.configure(r.scope(),r.edit(),r.key()));}
 @PostMapping(PayInternalPaths.SV_POLICY_CAPABILITY)
 public ResponseEntity<?> capability(@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->readiness.capability());}
 @PostMapping(PayInternalPaths.SV_POLICY_PLAN_BINDING)
 public ResponseEntity<?> planBinding(@RequestBody PayStoredValuePolicyRequests.PlanBinding r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->query.planBinding(r.tenant(),r.entity(),r.store(),r.version()));}
}
