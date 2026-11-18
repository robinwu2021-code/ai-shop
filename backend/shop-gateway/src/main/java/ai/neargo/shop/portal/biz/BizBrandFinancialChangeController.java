package ai.neargo.shop.portal.biz;
import ai.neargo.shop.merchantbridge.*;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizBrandFinancialChangeController {
 private final ManagementAccess access;private final BrandFinancialChangeCoordinator changes;
 public BizBrandFinancialChangeController(ManagementAccess access,BrandFinancialChangeCoordinator changes){this.access=access;this.changes=changes;}
 public record Start(String managementNo,String toBrandNo,long expectedStoreRevision,String reason){public Start{ContractChecks.id(managementNo);ContractChecks.optionalId(toBrandNo);ContractChecks.require(expectedStoreRevision>=0&&reason!=null&&!reason.isBlank()&&reason.length()<=255);}}
 public record Resume(String managementNo){public Resume{ContractChecks.id(managementNo);}}
 @GetMapping("/biz/management/stores/{storeNo}/stored-value/financial-brand-change-original")
 public BrandFinancialChangeCoordinator.Lookup original(@PathVariable String storeNo,@RequestParam String managementNo,@RequestParam String commandKey){return changes.findOriginal(access.actor(),managementNo,storeNo,commandKey);}
 @PostMapping("/biz/management/stores/{storeNo}/stored-value/financial-brand-changes")
 public BrandFinancialChangeCoordinator.View start(@PathVariable String storeNo,@RequestBody Start r,@RequestHeader("Idempotency-Key")String key){return changes.start(access.actor(),r.managementNo(),storeNo,r.toBrandNo(),r.expectedStoreRevision(),r.reason(),key);}
 @GetMapping("/biz/management/stored-value/financial-brand-changes/{changeNo}")
 public BrandFinancialChangeCoordinator.View read(@PathVariable String changeNo,@RequestParam String managementNo){return changes.read(access.actor(),managementNo,changeNo);}
 @PostMapping("/biz/management/stored-value/financial-brand-changes/{changeNo}/resumptions")
 public BrandFinancialChangeCoordinator.View resume(@PathVariable String changeNo,@RequestBody Resume r){return changes.resume(access.actor(),r.managementNo(),changeNo);}
}
