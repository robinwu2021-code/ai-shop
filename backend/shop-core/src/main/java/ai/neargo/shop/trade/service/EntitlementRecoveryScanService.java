package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.ScanPage;

/** Trusted background scanner only; persisted business outcomes, not cursor memory, determine completion. */
public interface EntitlementRecoveryScanService {
    ScanPage scan(String tenantNo,Kind kind,long afterId,int limit);
}
