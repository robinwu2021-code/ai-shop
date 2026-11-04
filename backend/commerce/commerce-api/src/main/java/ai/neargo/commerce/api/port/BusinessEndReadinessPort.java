package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.catalog.BusinessEndDraft;

import java.util.Set;

/**
 * Trusted deployment registry; must validate exact client/route/acceptance references. No self
 * certification.
 */
public interface BusinessEndReadinessPort {
    default java.util.List<ai.neargo.commerce.api.catalog.BusinessEndDeploymentOption> acceptedDeployments(String tenantNo) {
        return java.util.List.of();
    }

    boolean verified(String tenantNo, BusinessEndDraft registration);

    /**
     * Authoritative required base entry codes per type; must be nonempty before industry
     * publication.
     */
    Set<String> requiredEntitlementCodes(String tenantNo, String typeCode);
}
