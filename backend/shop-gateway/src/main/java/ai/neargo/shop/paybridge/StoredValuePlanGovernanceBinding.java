package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.*;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class StoredValuePlanGovernanceBinding {
 private final StoredValuePolicyAppService pay;private final StoredValuePolicyFactsPort facts;private final boolean enabled;
 public StoredValuePlanGovernanceBinding(StoredValuePolicyAppService pay,StoredValuePolicyFactsPort facts,@Value("${shop.pay.stored-value.governance-enabled:false}")boolean enabled){this.pay=pay;this.facts=facts;this.enabled=enabled;}
 public StoredValuePolicies.StoreScope resolve(String tenant,String entity,String store,String planVersion){if(!enabled)return null;var binding=pay.planBinding(tenant,entity,store,planVersion);var current=facts.current(tenant,entity,store,binding.activityVersionNo());if(current==null||!StoredValuePolicies.sameStoreIdentity(current,binding.scope())||!entity.equals(binding.entityNo()))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);return current;}
}
