package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.ActorContext;

/**
 * Host verifies actor/session and platform RBAC. Never derive platform permissions from merchant
 * purchase.
 */
@FunctionalInterface
public interface CatalogAuthorizationPort {
    boolean allowed(ActorContext actor, String capability);
}
