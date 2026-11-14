package ai.neargo.shop.event.outbox;

import java.util.Set;

/**
 * An explicitly installed workflow can replace archival intent for exactly its declared source/type
 * routes.
 */
public interface OutboxRouteContribution {
    Set<String> consumedArchiveRoutes();
}
