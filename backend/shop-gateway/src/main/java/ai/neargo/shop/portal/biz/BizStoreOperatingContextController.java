package ai.neargo.shop.portal.biz;
import ai.neargo.shop.merchant.brand.*;
import ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizStoreOperatingContextController {
 private final ManagementAccess access;private final BrandService brands;private final ManagementQueryService queries;
 public BizStoreOperatingContextController(ManagementAccess access,BrandService brands,ManagementQueryService queries){this.access=access;this.brands=brands;this.queries=queries;}
 @GetMapping("/biz/stores/{storeNo}/operating-context") public Map<String,Object> context(@PathVariable String storeNo,@RequestParam String managementNo){return queries.operating(access.actor(),managementNo,storeNo);}
 @PostMapping("/biz/stores/{storeNo}/brand-changes/check") public Map<String,Object> check(@PathVariable String storeNo,@RequestBody ChangeCheck c){return brands.check(access.actor(),storeNo,c);}
 @PostMapping("/biz/stores/{storeNo}/brand-changes") public Map<String,Object> submit(@PathVariable String storeNo,@RequestBody ChangeSubmit c,@RequestHeader("Idempotency-Key") String key){return brands.submit(access.actor(),storeNo,c,key);}
 @GetMapping("/biz/management/brand-changes/{changeNo}") public Map<String,Object> change(@PathVariable String changeNo){return brands.change(access.actor(),changeNo);}
 @PostMapping("/biz/management/brand-changes/{changeNo}/decisions") public Map<String,Object> decide(@PathVariable String changeNo,@RequestBody ChangeDecision c,@RequestHeader("Idempotency-Key") String key){return brands.decide(access.actor(),changeNo,c,key);}
}
