package ai.neargo.shop.pay.points.lifecycle;

import ai.neargo.shop.pay.points.model.AccountSnapshot;

/**
 * Metadata-only expiry decision; caller holds the type/pool lock for an authoritative financial
 * operation.
 */
public interface AccountExpiryGuard {
    boolean overdue(AccountSnapshot account, long now);
}
