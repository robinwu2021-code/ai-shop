package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Immutable asset entries join the caller's payment transaction. */
public interface EntitlementPostingService {
    void post(EntitlementContext context, EntInstance asset, String type, String operation,
              int unitsDelta, int reservedDelta, String bucketKey, String originEntryNo);
}
