package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.port.BusinessEndReadinessPort;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;

import java.util.*;

/** Deployment-owned acceptance manifest. No catalog/HTTP writer can add an accepted deployment. */
final class ShopMallDeploymentRegistry implements BusinessEndReadinessPort {
    private final List<BusinessEndDeploymentOption> entries;
    private final Map<String, Set<String>> required;

    ShopMallDeploymentRegistry(
            List<BusinessEndDeploymentOption> entries, Map<String, Set<String>> required) {
        var ids = new HashSet<String>();
        for (var e : entries) {
            if (e.deploymentNo() == null
                    || e.deploymentNo().isBlank()
                    || !ids.add(e.deploymentNo())
                    || e.clientId() == null
                    || e.clientId().isBlank()
                    || e.acceptanceRef() == null
                    || e.acceptanceRef().isBlank()
                    || e.allowedBusinessTypes().isEmpty()
                    || !installedPolicy(e))
                throw new IllegalArgumentException(
                        "Accepted deployment must identify an installed client/route contract and"
                            + " actual acceptance reference");
        }
        this.entries = List.copyOf(entries);
        var copy = new HashMap<String, Set<String>>();
        required.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        this.required = Map.copyOf(copy);
    }

    /**
     * 能力端（TDD-子行业与能力端验收 A1/A2）：行业、营销、连锁是商户 App 里的一组功能，没有独立客户端与路由，
     * 挂商户 App 的 BIZ 路由验收。码必须等于权益码，且只能是这几个；POS 路由仍只能是 POS 本身。
     */
    static final Set<String> BIZ_CAPABILITIES =
            Set.of("END_RETAIL", "END_FNB", "END_SERVICE", "END_MARKETING", "END_CHAIN");

    private static boolean installedPolicy(BusinessEndDeploymentOption e) {
        if (e.code() == null || !e.code().equals(e.entitlementCode())) return false;
        return switch (e.routePolicyRef() == null ? "" : e.routePolicyRef()) {
            case "commerce-route-v1:BIZ" ->
                    CommerceRoutePolicy.BIZ.equals(e.code()) || BIZ_CAPABILITIES.contains(e.code());
            case "commerce-route-v1:POS" -> CommerceRoutePolicy.POS.equals(e.code());
            default -> false;
        };
    }

    public List<BusinessEndDeploymentOption> acceptedDeployments(String tenant) {
        return "MAIN".equals(tenant) ? entries : List.of();
    }

    public boolean verified(String tenant, BusinessEndDraft end) {
        return "MAIN".equals(tenant)
                && end.scope() == EndScope.STORE
                && entries.stream()
                        .anyMatch(
                                e ->
                                        e.code().equals(end.code())
                                                && e.entitlementCode().equals(end.entitlementCode())
                                                && e.clientId().equals(end.clientId())
                                                && e.routePolicyRef().equals(end.routePolicyRef())
                                                && e.acceptanceRef().equals(end.acceptanceRef())
                                                && !end.allowedBusinessTypes().isEmpty()
                                                && e.allowedBusinessTypes()
                                                        .containsAll(end.allowedBusinessTypes()));
    }

    public Set<String> requiredEntitlementCodes(String tenant, String type) {
        return "MAIN".equals(tenant) ? required.getOrDefault(type, Set.of()) : Set.of();
    }
}
