package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.EffectivePolicy;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PlanBinding;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyLocation;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyView;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import java.util.List;

/** Reads effective issuance policies and their store authority. */
public interface StoredValueIssuePolicyService {
    EffectivePolicy evaluate(StoreScope scope);

    EffectivePolicy evaluateLocked(StoreScope scope);

    PolicyLocation locate(String tenant, String policyNo);

    PlanBinding planBinding(String tenant, String entity, String store, String version);

    List<PolicyView> list(CatalogScope scope);

    void requireStoreAccess(StoreScope scope, String entity, String issuer);
}
