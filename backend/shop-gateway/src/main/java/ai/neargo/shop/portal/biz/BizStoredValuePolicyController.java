package ai.neargo.shop.portal.biz;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.StoredValuePolicyAuthorityService;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
@ConditionalOnProperty(name="shop.pay.stored-value.governance-enabled",havingValue="true")
public class BizStoredValuePolicyController {
 private final ManagementAccess access;private final StoredValuePolicyAuthorityService authority;private final StoredValuePolicyAppService pay;
 public BizStoredValuePolicyController(ManagementAccess access,StoredValuePolicyAuthorityService authority,StoredValuePolicyAppService pay){this.access=access;this.authority=authority;this.pay=pay;}
 public record Create(String managementNo,String targetType,String targetNo,String currency,Rules rules){public Create{ContractChecks.id(managementNo);ContractChecks.id(targetNo);ContractChecks.present(rules);}}
 public record RulesEdit(Rules rules,long expectedRevision){public RulesEdit{ContractChecks.present(rules);ContractChecks.require(expectedRevision>=0);}}
 public record Edit(String managementNo,RulesEdit edit){public Edit{ContractChecks.id(managementNo);ContractChecks.present(edit);}}
 public record PreparePermissions(String managementNo){public PreparePermissions{ContractChecks.id(managementNo);}}
 @PostMapping("/biz/management/stored-value/policy-permissions/prepare")
 public java.util.Map<String,Boolean> preparePermissions(@RequestBody PreparePermissions r){authority.prepareOwnPermissions(access.actor(),r.managementNo());return java.util.Map.of("prepared",true);}
 @GetMapping("/biz/management/stored-value/policy-targets")
 public List<StoredValuePolicyAuthorityService.PolicyTarget> targets(@RequestParam String managementNo){return authority.policyTargets(access.actor(),managementNo);}
 @GetMapping("/biz/management/stored-value/policies")
 public List<PolicyView> list(@RequestParam String managementNo,@RequestParam(required=false)String targetType,@RequestParam(required=false)String targetNo,@RequestParam String currency){return pay.list(authority.catalog(access.actor(),managementNo,targetType,targetNo,currency));}
 @PostMapping("/biz/management/stored-value/policies")
 public PolicyView create(@RequestBody Create r,@RequestHeader("Idempotency-Key")String key){return pay.configure(authority.target(access.actor(),r.managementNo(),r.targetType(),r.targetNo(),r.currency(),"sv.policy.manage"),new PolicyEdit(r.rules(),0,true),key);}
 @PutMapping("/biz/management/stored-value/policies/{policyNo}")
 public PolicyView edit(@PathVariable String policyNo,@RequestBody Edit r,@RequestHeader("Idempotency-Key")String key){var actor=access.actor();var location=pay.locate(actor.tenantNo(),policyNo);if(!r.managementNo().equals(location.managementNo()))throw BizException.of(ErrorCode.NOT_FOUND);return pay.configure(authority.target(actor,location.managementNo(),location.targetType(),location.targetNo(),location.currency(),"sv.policy.manage"),new PolicyEdit(r.edit().rules(),r.edit().expectedRevision()),key);}
 @GetMapping("/biz/stores/{storeNo}/stored-value/effective-policy")
 public EffectivePolicy effective(@PathVariable String storeNo,@RequestParam String managementNo,@RequestParam String currency){return pay.evaluate(authority.store(access.actor(),managementNo,storeNo,currency));}
}
