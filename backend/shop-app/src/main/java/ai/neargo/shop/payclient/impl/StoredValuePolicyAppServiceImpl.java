package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.StoredValuePolicyAppService;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValuePolicyAppServiceImpl implements StoredValuePolicyAppService {
 private final ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyService query; private final ai.neargo.shop.pay.storedvalue.policy.StoredValuePolicyConfigurationService configuration;
 public StoredValuePolicyAppServiceImpl(ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyService query,ai.neargo.shop.pay.storedvalue.policy.StoredValuePolicyConfigurationService configuration){this.query=query;this.configuration=configuration;}
 @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.policy.StoredValueGovernanceCapabilityService readiness;
 public PlanBinding planBinding(String tenant,String entity,String store,String version){return query.planBinding(tenant,entity,store,version);}
 public Capability capability(){return readiness.capability();}
 public PolicyLocation locate(String tenant,String no){return query.locate(tenant,no);}
 public List<PolicyView> list(CatalogScope scope){return query.list(scope);}
 public EffectivePolicy evaluate(StoreScope scope){return query.evaluate(scope);}
 public PolicyView configure(PolicyAuthority scope,PolicyEdit edit,String key){return configuration.configure(scope,edit,key);}
}
