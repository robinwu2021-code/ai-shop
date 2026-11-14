package ai.neargo.shop.food.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;

/** Missing original capability must not invent an always-open calendar. */
public interface StoreCalendarSourceService {
    default Facts capture(Query query) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }
}
