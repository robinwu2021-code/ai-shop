package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

/** Host must verify authoritative session/member state for issue and write capabilities; never derive actors from request bodies. */
public interface ActorAuthorizationPort {
 AuthorizationDecision authorize(ActorContext actor, StoreRef store, String capability);

}
