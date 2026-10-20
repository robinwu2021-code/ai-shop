package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.Set;

/** Trusted application scope; never deserialize this value from a merchant request. */
public record ActivityActor(String tenantNo, String entityNo, String actorNo, Set<String> allowedStores) {
    public ActivityActor {
        ContractChecks.id(tenantNo);
        ContractChecks.require(tenantNo.length() <= 32);
        ContractChecks.id(entityNo);
        ContractChecks.id(actorNo);
        allowedStores = allowedStores == null ? null : Set.copyOf(allowedStores);
    }
}
