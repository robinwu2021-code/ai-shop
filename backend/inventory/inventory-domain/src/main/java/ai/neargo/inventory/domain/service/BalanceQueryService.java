package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.domain.entity.InvStockBalance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** 余额查询（TDD §2.11）：仓 × 物料，含不可用。 */
public interface BalanceQueryService {

    /** @param locationId 空 = 全部库位；@param itemId 空 = 全部物料 */
    List<InvStockBalance> balances(String ownerId, String locationId, String itemId);

    /**
     * 多账本批量余额（TDD §2.19）：宿主在业务侧做「连锁看所有门店」这类汇总用，服务不知道这些账本为什么一起查。
     * 每本账单独判定：不属于本系统 / 不存在 → {@code NOT_FOUND}，写入方不是独立服务 → {@code NOT_WRITER}，
     * 同号多本没给类型 → {@code owner_type_required}；一项出错不影响其他项。
     *
     * @param itemRefs 宿主物料编号；空 = 全部物料
     */
    List<OwnerBalances> acrossOwners(String systemCode, List<OwnerKey> owners, Set<String> itemRefs);

    record OwnerKey(String type, String ref) {
    }

    record Row(String itemRef, String itemId, String locationId, String locationRef, BigDecimal onHand,
               BigDecimal reserved, BigDecimal unavailable, BigDecimal available) {
    }

    /** {@code error} 非空时 {@code balances} 为空，取值见 {@link #acrossOwners} */
    record OwnerBalances(String ownerType, String ownerRef, String error, List<Row> balances) {
    }
}
