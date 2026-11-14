package ai.neargo.shop.portal.internal;
import ai.neargo.shop.paybridge.StoredValueMigrationOperatorService;
import ai.neargo.shop.svc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** Internal token plus server-file approval; not a merchant or public operations endpoint. */
@RestController
public class InternalStoredValueMigrationOperatorEndpoint {
 private final StoredValueMigrationOperatorService service;private final String token;
 public InternalStoredValueMigrationOperatorEndpoint(StoredValueMigrationOperatorService service,@Value("${shop.services.internal-token:}") String token){this.service=service;this.token=token;}
 public record Request(String operatorNo,String approvalNo){}
 @PostMapping("/internal/stored-value/maintenance/{action}")
 public ResponseEntity<?> execute(@PathVariable String action,@RequestBody Request request,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.execute(action,request.operatorNo(),request.approvalNo()));}
 @GetMapping("/internal/stored-value/commands/{commandNo}")
 public ResponseEntity<?> command(@PathVariable String commandNo,@RequestParam String operatorNo,@RequestParam String approvalNo,@RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false)String given){return InternalServiceResponses.invoke(token,given,()->service.command(operatorNo,approvalNo,commandNo));}
}
