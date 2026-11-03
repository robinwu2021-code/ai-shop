package ai.neargo.commerce.api.foundation;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;
import java.util.List;

/**
 * Both checks must use the live merchant session. Membership belongs to the exact requested store.
 */
public interface FoundationAuthorityPort {
    Instant account(ActorContext actor, Instant now);

    Member member(ActorContext actor, StoreRef store, Instant now);

    record Member(
            StoreSnapshot store,
            String storeStatus,
            String entityStatus,
            boolean owner,
            List<String> roles,
            boolean canProcure,
            Instant sessionExpiry) {}
}
