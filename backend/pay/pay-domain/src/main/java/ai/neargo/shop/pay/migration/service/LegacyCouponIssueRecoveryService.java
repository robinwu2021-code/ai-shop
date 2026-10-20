package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.*;

/** Domain contract; transactional behavior remains in the implementation. */
public interface LegacyCouponIssueRecoveryService {
public record Result(int scanned,int completed,int deferred,int failed,long nextId) { }
    Result recover(String tenant,long afterId,int limit);
}
