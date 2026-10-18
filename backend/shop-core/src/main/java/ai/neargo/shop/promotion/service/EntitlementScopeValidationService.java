package ai.neargo.shop.promotion.service;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;

/** Write preflight for authenticated owner context; never bind owner identifiers from a public body. */
public interface EntitlementScopeValidationService {
    void validate(String tenantNo, String entityNo, RedemptionScope scope);

    void validateDefinition(String tenantNo, String entityNo, EntitlementDefinition definition);
}
