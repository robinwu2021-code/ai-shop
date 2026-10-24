package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.storedvalue.issuer.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalStoredValueIssuerRechargeEndpoint {
 private final StoredValueBrandRechargeService service;private final StoredValueIssuerQueryService queries;private final String token;
 public InternalStoredValueIssuerRechargeEndpoint(StoredValueBrandRechargeService service,StoredValueIssuerQueryService queries,@Value("${shop.services.internal-token:}")String token){this.service=service;this.queries=queries;this.token=token;}
 @PostMapping(PayInternalPaths.SV_EXECUTION_ISSUERS)
 public ResponseEntity<?> issuers(@RequestBody ExecutionScope r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->queries.storeOptions(r));}
 @PostMapping(PayInternalPaths.SV_BRAND_RECHARGE_OPTIONS)
 public ResponseEntity<?> options(@RequestBody PayStoredValueIssuerRechargeRequests.Options r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.options(r.query(),r.cursor(),r.size()));}
 @PostMapping(PayInternalPaths.SV_BRAND_RECHARGE_CONFIRM)
 public ResponseEntity<?> confirm(@RequestBody PayStoredValueIssuerRechargeRequests.Confirm r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.confirm(r.context(),r.entityNo(),r.command()));}
 @PostMapping(PayInternalPaths.SV_BRAND_RECHARGE_RESUME)
 public ResponseEntity<?> resume(@RequestBody PayStoredValueIssuerRechargeRequests.Replay r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.resume(r.context(),r.entityNo(),r.command()));}
 @PostMapping(PayInternalPaths.SV_BRAND_RECHARGE_REPLAY)
 public ResponseEntity<?> replay(@RequestBody PayStoredValueIssuerRechargeRequests.Replay r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.replay(r.context(),r.entityNo(),r.command()));}
 @PostMapping(PayInternalPaths.SV_BRAND_RECHARGE_CANCEL_CREATE)
 public ResponseEntity<?> cancel(@RequestBody PayStoredValueIssuerRechargeRequests.Replay r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.cancelCreation(r.context(),r.entityNo(),r.command()));}
 @PostMapping(PayInternalPaths.SV_FUNDED_CUSTOMERS)
 public ResponseEntity<?> funded(@RequestBody ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.FundedPage r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->queries.funded(r.scope(),r.cursor(),r.size()));}
}
