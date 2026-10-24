package ai.neargo.shop.payclient;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.util.List;
public interface StoredValuePolicyAppService {
 Capability capability();
 PlanBinding planBinding(String tenant,String entity,String store,String version);
 PolicyLocation locate(String tenant,String policyNo);
 List<PolicyView> list(CatalogScope scope);
 EffectivePolicy evaluate(StoreScope scope);
 PolicyView configure(PolicyAuthority scope,PolicyEdit edit,String key);
}
