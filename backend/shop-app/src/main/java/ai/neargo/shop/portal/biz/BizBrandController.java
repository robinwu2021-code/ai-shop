package ai.neargo.shop.portal.biz;
import ai.neargo.shop.merchant.brand.*;
import ai.neargo.shop.merchant.brand.ManagementModels.*;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizBrandController {
 private final ManagementAccess access;private final BrandService brands;private final ManagementQueryService queries;
 public BizBrandController(ManagementAccess access,BrandService brands,ManagementQueryService queries){this.access=access;this.brands=brands;this.queries=queries;}
 @GetMapping("/biz/management/brands") public Page list(@RequestParam String managementNo,@RequestParam(required=false) String status,@RequestParam(required=false) String keyword,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return queries.list(access.actor(),managementNo,"BRAND",null,keyword,status,cursor,size);}
 @PostMapping("/biz/management/brands") public Map<String,Object> create(@RequestBody BrandCreate c,@RequestHeader("Idempotency-Key") String key){return brands.create(access.actor(),c,key);}
 @GetMapping("/biz/management/brands/{brandNo}") public Map<String,Object> detail(@PathVariable String brandNo){return brands.detail(access.actor(),brandNo);}
 @PutMapping("/biz/management/brands/{brandNo}") public Map<String,Object> edit(@PathVariable String brandNo,@RequestBody BrandEdit c,@RequestHeader("Idempotency-Key") String key){return brands.edit(access.actor(),brandNo,c,key);}
 @PostMapping("/biz/management/brands/{brandNo}/status-changes") public Map<String,Object> status(@PathVariable String brandNo,@RequestBody StatusChange c,@RequestHeader("Idempotency-Key") String key){return brands.status(access.actor(),brandNo,c,key);}
 @GetMapping("/biz/management/brands/{brandNo}/stores") public Page stores(@PathVariable String brandNo,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){var detail=brands.detail(access.actor(),brandNo);return queries.list(access.actor(),(String)detail.get("managementNo"),"STORE",brandNo,null,null,cursor,size);}
 @GetMapping("/biz/management/brands/{brandNo}/audit-events") public Page audit(@PathVariable String brandNo,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return queries.audit(access.actor(),brandNo,cursor,size);}
}
