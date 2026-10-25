package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.IssueScope;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.EffectivePolicy;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PlanBinding;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyLocation;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyView;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueIssuePolicyService {
    EffectivePolicy evaluate(IssueScope scope);

    EffectivePolicy evaluateLocked(IssueScope scope);

    ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyLocation locate(String tenant, String policyNo);

    PlanBinding planBinding(String tenant,String entity,String store,String version);

    java.util.List<PolicyView> list(CatalogScope scope);

    void requireStoreAccess(IssueScope scope,String entity,String issuer);
}
