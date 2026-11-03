package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.catalog.BusinessEndDraft;

import java.util.Set;

/**
 * Trusted deployment registry; must validate exact client/route/acceptance references. No self
 * certification.
 */
public interface BusinessEndReadinessPort {
    boolean verified(String tenantNo, BusinessEndDraft registration);

    /**
     * Authoritative required base entry codes per type; must be nonempty before industry
     * publication.
     */
    Set<String> requiredEntitlementCodes(String tenantNo, String typeCode);
}
