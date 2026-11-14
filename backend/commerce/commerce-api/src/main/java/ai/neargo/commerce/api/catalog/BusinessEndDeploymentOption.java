package ai.neargo.commerce.api.catalog;

import java.util.List;

/**
 * Server-owned accepted deployment option; operators choose its id, never manufacture proof
 * strings.
 */
public record BusinessEndDeploymentOption(
        String deploymentNo,
        String name,
        String code,
        String entitlementCode,
        List<String> allowedBusinessTypes,
        String clientId,
        String routePolicyRef,
        String acceptanceRef) {}
