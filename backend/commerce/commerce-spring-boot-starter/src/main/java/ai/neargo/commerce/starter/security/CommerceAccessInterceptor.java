package ai.neargo.commerce.starter.security;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;

import java.util.Optional;

/** Called after strict store selection and before dispatch to the merchant handler. */
public final class CommerceAccessInterceptor {
    private final EndAccessFacade access;

    public CommerceAccessInterceptor(EndAccessFacade access) {
        this.access = access;
    }

    public AccessCheck authorize(
            CommerceRoutePolicy.Route route,
            ActorContext actor,
            StoreRef requested,
            StoreRef resolved,
            String client,
            String proof) {
        if (route.lane() != CommerceRoutePolicy.Lane.END_PROTECTED)
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        var check =
                new AccessCheck(
                        actor,
                        requested,
                        resolved,
                        Optional.ofNullable(route.pathStore())
                                .map(s -> new StoreRef(actor.tenantNo(), s)),
                        CommerceRoutePolicy.endCode(route.entitlement()),
                        route.entitlement(),
                        client,
                        route.capability(),
                        Optional.ofNullable(proof));
        var decision = access.check(check);
        if (decision.status() != AccessStatus.ALLOWED) throw new AccessDenied(decision);
        return check;
    }
}
