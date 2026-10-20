package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision;
import ai.neargo.shop.spi.entitlement.SourceRef;

/** All monetary mutations are inside one pay-owned transaction after source/consent preflight. */
public interface StoredValueRedemptionPostingService {
    HoldView prepare(EntitlementContext context, Prepared frozen);
    HoldView finish(EntitlementContext context, String operationNo, SourceRef source, DecisionRef decision,
                    boolean commit, SourceDecision truth);
}
