package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.AbortGrant;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantDecision;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantView;

/** Resolves external owner facts before entering the payment resource transaction. */
public interface EntitlementGrantService {
    GrantView prepare(EntitlementContext context, GrantCommand command);
    GrantView commit(EntitlementContext context, GrantDecision command);
    GrantView abort(EntitlementContext context, AbortGrant command);
    GrantView query(EntitlementContext context, String entityNo, String grantNo);
}
