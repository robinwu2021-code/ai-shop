package ai.neargo.inventory.domain.service;

/**
 * 宿主的引用 → 本服务的编号（M8 T8.3）。宿主按自己的号调用（商城：SKU 号、门店号），
 * 不必先拿到库存服务内部的物料号与库位号 —— 切流后的宿主不该还要连库存库去换号。
 */
public interface HostReferenceService {

    /** 物料：先认物料自己的宿主编号（{@code external_ref}），再认商城的 SKU 映射；都没有为空 */
    String itemIdOf(String ownerId, String itemRef);

    /** 反查：物料在宿主里的编号；没有为空 */
    String itemRefOf(String ownerId, String itemId);

    /** 门店 / 仓的宿主编号 → 实际扣库存的库位（门店共用货源时落到货源库位）；没有为空 */
    String stockLocationOf(String ownerId, String locationRef);
}
