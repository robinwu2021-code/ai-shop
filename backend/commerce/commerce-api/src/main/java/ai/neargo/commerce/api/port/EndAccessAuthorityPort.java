package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.ActorContext;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.common.StoreSnapshot;

import java.time.Instant;

/** Host-owned primary snapshot of the authenticated session, selected store and required duties. */
public interface EndAccessAuthorityPort {
    record Authority(StoreSnapshot store, Instant sessionExpiry, boolean permitted) {}

    Authority verify(
            ActorContext actor, StoreRef store, String entitlement, String capability, Instant now);
}
