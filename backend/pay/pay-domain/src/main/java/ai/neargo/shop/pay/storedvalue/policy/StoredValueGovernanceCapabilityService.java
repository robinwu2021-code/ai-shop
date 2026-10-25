package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.Capability;

/** Reads the installed stored-value governance capability. */
public interface StoredValueGovernanceCapabilityService {
    Capability capability();
}
