package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyEdit;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyView;

/** Updates issuance policies under the existing policy authority. */
public interface StoredValuePolicyConfigurationService {
    PolicyView configure(PolicyAuthority scope, PolicyEdit edit, String key);
}
