package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueReleaseService;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalStoredValueReleaseEndpoint {
 private final StoredValueReleaseService target;private final String token;
 public InternalStoredValueReleaseEndpoint(StoredValueReleaseService target,@Value("${shop.services.internal-token:}")String token){this.target=target;this.token=token;}
 @PostMapping(PayInternalPaths.SV_RELEASE_CHECK)
 public ResponseEntity<?> check(@RequestBody Prepare r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->target.check(r));}
 @PostMapping(PayInternalPaths.SV_RELEASE_PREPARE)
 public ResponseEntity<?> prepare(@RequestBody Prepare r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->target.prepare(r));}
 @PostMapping(PayInternalPaths.SV_RELEASE_ACTIVATE)
 public ResponseEntity<?> activate(@RequestBody Activate r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->target.activate(r));}
 @PostMapping(PayInternalPaths.SV_RELEASE_STOP)
 public ResponseEntity<?> stop(@RequestBody Stop r,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->target.stop(r));}
}
