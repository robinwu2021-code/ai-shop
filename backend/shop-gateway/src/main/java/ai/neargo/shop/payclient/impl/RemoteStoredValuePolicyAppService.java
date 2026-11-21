package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValuePolicyAppService implements StoredValuePolicyAppService {
 private final ai.neargo.shop.pay.client.PayInternalApi target;
 public RemoteStoredValuePolicyAppService(ai.neargo.shop.pay.client.PayInternalApi target){this.target=target;}
 public PlanBinding planBinding(String tenant,String entity,String store,String version){return RemoteEntitlementCalls.call(()->target.svPolicyPlanBinding(new ai.neargo.shop.pay.client.PayStoredValuePolicyRequests.PlanBinding(tenant,entity,store,version)));}
 public Capability capability(){return RemoteEntitlementCalls.call(target::svPolicyCapability);}
 public PolicyLocation locate(String tenant,String no){return RemoteEntitlementCalls.call(()->target.svPolicyLocate(new ai.neargo.shop.pay.client.PayStoredValuePolicyRequests.Locate(tenant,no)));}
 public List<PolicyView> list(CatalogScope scope){return RemoteEntitlementCalls.call(()->target.svPolicyList(scope));}
 public EffectivePolicy evaluate(StoreScope scope){return RemoteEntitlementCalls.call(()->target.svPolicyEvaluate(scope));}
 public PolicyView configure(PolicyAuthority scope,PolicyEdit edit,String key){return RemoteEntitlementCalls.call(()->target.svPolicyConfigure(new ai.neargo.shop.pay.client.PayStoredValuePolicyRequests.Configure(scope,edit,key)));}
}
