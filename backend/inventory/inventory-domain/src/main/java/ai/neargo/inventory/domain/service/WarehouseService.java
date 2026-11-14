package ai.neargo.inventory.domain.service;

/**
 * 仓库维护与停用（TDD §2.14 inv_location，T2.4）。
 *
 * <p>停用守卫逐项列原因：有余额、有未完成单据、有冻结 —— 任何一项在，停用都会让货「在一个不存在的仓里」。
 */
public interface WarehouseService {

    /** 补丁语义：空字段不动。@param holderRef 宿主的持有方标记（TDD §2.19） */
    void update(Caller caller, String locationId, String name, String regionRef, String holderRef);

    /**
     * @throws ai.neargo.inventory.api.InventoryException {@code CONFLICT}，参数是全部不满足的原因
     *         （{@code BALANCE} / {@code OPEN_DOCUMENTS} / {@code HOLDS}），一次列全
     */
    void disable(Caller caller, String locationId);
}
