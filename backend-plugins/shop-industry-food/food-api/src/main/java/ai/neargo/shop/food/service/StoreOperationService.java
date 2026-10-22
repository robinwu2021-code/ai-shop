package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCalendarReq;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import java.time.LocalDate;

/** Restaurant policy operations; add later write methods only with their actual accepted implementation. */
public interface StoreOperationService {
    /** Reads the latest policy effective at the requested instant within the already authorized store scope. */
    StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query);
    /** Appends a policy atomically after comparing the latest published version, with actor-bound replay. */
    OperationVO createPolicy(String entityNo, String storeNo, String actorNo, CreatePolicyReq req,
            String idempotencyKey);
    /** Saves one business-day exception without modifying shared store hours. */
    OperationVO saveCalendar(String entityNo, String storeNo, LocalDate localDate, String actorNo,
            SaveCalendarReq req, String idempotencyKey);
}
