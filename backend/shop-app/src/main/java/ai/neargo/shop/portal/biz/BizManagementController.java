package ai.neargo.shop.portal.biz;
import ai.neargo.shop.merchant.brand.*;
import ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizManagementController {
 private final ManagementAccess access;private final ManagementScopeService scopes;private final ManagementQueryService queries;private final PaymentMerchantScopeService merchants;
 public BizManagementController(ManagementAccess access,ManagementScopeService scopes,ManagementQueryService queries,PaymentMerchantScopeService merchants){this.access=access;this.scopes=scopes;this.queries=queries;this.merchants=merchants;}
 @GetMapping("/biz/management/context") public Map<String,Object> context(){return scopes.context(access.actor());}
 @PostMapping("/biz/management/spaces") public Map<String,Object> create(@RequestBody SpaceCreate c,@RequestHeader("Idempotency-Key") String key){return scopes.create(access.actor(),c.name(),c.sourceAccountNo(),key);}
 @GetMapping("/biz/management/spaces/{managementNo}/resources") public Page resources(@PathVariable String managementNo,@RequestParam(required=false) String resourceType,@RequestParam(defaultValue="EFFECTIVE") String view,@RequestParam(required=false) String actorNo,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return queries.resources(access.actor(),managementNo,resourceType,view,actorNo,cursor,size);}
 @PostMapping("/biz/management/spaces/{managementNo}/grants") public Map<String,Object> grant(@PathVariable String managementNo,@RequestBody GrantCommand c){return scopes.grant(access.actor(),managementNo,c);}
 @DeleteMapping("/biz/management/spaces/{managementNo}/grants/{grantNo}") public Map<String,Object> revoke(@PathVariable String managementNo,@PathVariable String grantNo,@RequestParam long expectedRevision,@RequestParam String reason){return scopes.revoke(access.actor(),managementNo,grantNo,expectedRevision,reason);}
 @GetMapping("/biz/management/spaces/{managementNo}/entities") public Page entities(@PathVariable String managementNo,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return queries.list(access.actor(),managementNo,"ENTITY",null,null,null,cursor,size);}
 @GetMapping("/biz/management/spaces/{managementNo}/stores") public Page stores(@PathVariable String managementNo,@RequestParam(required=false) String brandNo,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return queries.list(access.actor(),managementNo,"STORE",brandNo,null,null,cursor,size);}
 @GetMapping("/biz/management/entities/{entityNo}/payment-merchants") public List<Map<String,Object>> merchants(@PathVariable String entityNo,@RequestParam String managementNo,@RequestParam(required=false) String payChannel,@RequestParam(required=false) String payScene,@RequestParam(required=false) String storeNo){return merchants.candidates(access.actor(),managementNo,entityNo,payChannel,payScene,storeNo);}
}
