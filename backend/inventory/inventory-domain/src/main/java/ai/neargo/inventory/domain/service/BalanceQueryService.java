package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.domain.entity.InvStockBalance;

import java.util.List;

/** 余额查询（TDD §2.11）：仓 × 物料，含不可用。 */
public interface BalanceQueryService {

    /** @param locationId 空 = 全部库位；@param itemId 空 = 全部物料 */
    List<InvStockBalance> balances(String ownerId, String locationId, String itemId);
}
