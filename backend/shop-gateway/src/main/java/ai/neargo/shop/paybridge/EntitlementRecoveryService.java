package ai.neargo.shop.paybridge;

import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;

/** Bounded original-operation recovery; a pending source is never inferred to be cancelled from elapsed time. */
public interface EntitlementRecoveryService {
    Result recover(String tenantNo,Kind kind,long afterId,int limit);
    record Result(int scanned,int completed,int deferred,int failed,long nextId) { }
}
