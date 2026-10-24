package ai.neargo.shop.portal.biz;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.Capability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizStoredValueGovernanceCapabilityController {
 private final StoredValuePolicyAppService pay;private final boolean enabled;
 public BizStoredValueGovernanceCapabilityController(StoredValuePolicyAppService pay,@Value("${shop.pay.stored-value.governance-enabled:false}")boolean enabled){this.pay=pay;this.enabled=enabled;}
 @GetMapping("/biz/stored-value/governance-capability")
 public Capability capability(){if(!enabled)return new Capability(1,false);var owner=pay.capability();return new Capability(1,owner!=null&&owner.protocol()==1&&owner.enabled());}
}
