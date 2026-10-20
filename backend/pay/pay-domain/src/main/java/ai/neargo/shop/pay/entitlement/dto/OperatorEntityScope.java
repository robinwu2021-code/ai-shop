package ai.neargo.shop.pay.entitlement.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.Set;

/** Trusted session projection only: null is ALL, an empty set authorizes no merchant records. */
public record OperatorEntityScope(String tenantNo, String actorNo, Set<String> entityNos) {
    public OperatorEntityScope {
        ContractChecks.id(tenantNo); ContractChecks.id(actorNo);
        if (entityNos != null) {
            entityNos = Set.copyOf(entityNos);
            ContractChecks.require(entityNos.size() <= 1000);
            entityNos.forEach(ContractChecks::id);
        }
    }
}
