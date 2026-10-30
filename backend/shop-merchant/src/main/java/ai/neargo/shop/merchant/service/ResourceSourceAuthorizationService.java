package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.policy.ResourcePlanCodec.FrozenPlan;
import ai.neargo.shop.merchant.service.ResourceCommandService.Execution;
import ai.neargo.shop.merchant.service.ResourceCommandService.Scope;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Decision;

/** Source permission consumer only; providers own durable authorization, full source intent and fence retention. */
public interface ResourceSourceAuthorizationService {
    /** Calls the registered source outside resource transactions, always for the original accepted command. */
    Resolution authorize(Scope scope, String commandNo);

    /** Queries the original source fence after uncertainty; never guesses rejection or replaces the command. */
    Resolution recover(Scope scope, String commandNo);

    /** Rechecks the private permission against the live execution and again checks expiry before local commit. */
    FrozenPlan requireAuthorized(Execution execution, Authorization authorization);

    /** Opaque local proof; never serialize, accept from HTTP, or use it in place of a durable source query. */
    interface Authorization {
    }

    /** Non-authorized source states preserve their original truth without minting a permission. */
    record Resolution(Decision decision, Authorization authorization) {
    }
}
