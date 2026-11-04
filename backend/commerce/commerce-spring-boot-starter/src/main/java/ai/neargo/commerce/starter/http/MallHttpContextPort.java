package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.common.*;

/**
 * Host identity is independent of request JSON; implementations must verify current authoritative
 * facts.
 */
public interface MallHttpContextPort {
    record Buyer(ActorContext actor, StoreRef store) {}

    ActorContext operator(String capability);

    Buyer buyer(String pathStoreNo, String capability);

    /** Server-derived authenticated provider payer reference, never raw client-supplied payer. */
    String payerReference(String channel, String payMethod);
}
