package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CashShiftContracts.CashShiftVO;
import ai.neargo.shop.food.dto.CashShiftContracts.SubmitReq;

/** 现金交班（POS B14 / B15）。 */
public interface CashShiftService {
    /** 一次提交整张交班单；同一个 Idempotency-Key 重放返回同一张，内容不同则冲突。 */
    CashShiftVO submit(String entityNo, String storeNo, String actorNo, SubmitReq req, String key);

    CashShiftVO get(String entityNo, String storeNo, String shiftNo);
}
