package ai.neargo.commerce.api.foundation;

import ai.neargo.commerce.api.common.ActorContext;

import java.util.Set;

/** Optional selected-store capability. Invoked after the legacy catalog read transaction ends. */
@FunctionalInterface
public interface FoundationPurchaseAvailabilityPort {
    Set<String> availableSkuVersions(ActorContext actor, FoundationQueryFacade.PlanCatalog catalog);
}
