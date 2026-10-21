package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.dto.ScheduleQuery;
import ai.neargo.shop.merchant.dto.ScheduleRuleVO;
import ai.neargo.shop.merchant.dto.ScheduleVO;

/** Shared weekly maintenance preserves personnel identity and existing resource concurrency fences. */
public interface ScheduleService {
    /** Exposes working windows without implying appointment availability or processing leave exceptions. */
    ScheduleVO listSchedules(String entityNo, String currentStoreNo, String actorNo, ScheduleQuery query);
    /** Replaces rules with retained history; expectedRevision always fences the canonical resource root. */
    ScheduleRuleVO saveScheduleRule(String entityNo, String currentStoreNo, String actorNo,
            SaveScheduleRuleReq request, String idempotencyKey);
}
