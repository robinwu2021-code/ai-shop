package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.domain.entity.InvStockHold;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多原因冻结（TDD §2.7，T2.5）。
 *
 * <p>{@code available = onHand - reserved - unavailable}，{@code unavailable} 由本服务按
 * 「同一件至多计 1」重算后写进余额行：单件冻结按件去重；数量冻结按原因分别求和、取最大 ——
 * 盘点冻了这 10 件、人工又冻了其中 2 件，不可用的是 10 件，不是 12 件。
 */
public interface StockHoldService {

    /**
     * 冻结。冻不进已预留的货：可用量不够时拒绝。
     *
     * @param serialId SERIAL 物料的单件（M3 起）；数量物料为空
     * @throws ai.neargo.inventory.api.InventoryException {@code STOCK_SHORT} 可用量不够冻
     */
    String place(Caller caller, String itemId, String locationId, String serialId, BigDecimal qty,
                 String reason, String sourceRef, String note);

    /** 解除一条冻结。已解除的再解除是空操作（幂等）。 */
    void release(Caller caller, String holdId);

    /** 按来源整批解除（盘点作废 / 过账、退货业务确认）。返回解除了几条。 */
    int releaseBySource(Caller caller, String reason, String sourceRef);

    List<InvStockHold> active(String ownerId, String itemId, String locationId);
}
