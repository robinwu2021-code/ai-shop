package ai.neargo.shop.inventory.service;

/**
 * 防腐层：平台的键 ↔ 本域的键。
 *
 * <p><b>这是唯一知道外部存在的一层。</b>{@code entityNo} / {@code storeNo} / {@code skuNo}
 * 只允许出现在这里；领域服务一律只认 {@code ownerId} / {@code locationId} / {@code itemId}。
 * 少了这条约束，「可独立交付」就是一句愿景 —— 客户那边没有 {@code entityNo}。
 *
 * <p><b>投影是单向的</b>（平台 → 本域），唯一的反向是成本价，且平台侧没有写入口。
 * 反向多一条，就有了两个真相源，而两个真相源的冲突是静默的。
 */
public interface InventoryAclService {

    /** 平台主体 → 业主。**1:1**（已定）：多执照商家仍是一个业主 —— 货是同一批货。 */
    String ownerIdOf(String entityNo);

    /**
     * 平台门店 → 库位。
     *
     * <p>{@code storeNo} 为空时落到**默认库位** —— 那是存量「主体级库存」的落点，
     * 也是单库位商家的全部。主体级不是第二种表达，就是一个库位。
     */
    String locationIdOf(String entityNo, String storeNo);

    /** 平台 SKU → 物料。不存在时按需投影一条（保存即投影，已定）。 */
    String itemIdOf(String entityNo, String skuNo);

    /**
     * 投影一件商品。**幂等**：按 {@code (owner, AISHOP, skuNo)} 找，找到就更新展示字段。
     *
     * @param saleUnit 平台的 {@code sale_unit} → 本域的 {@code base_uom}。
     *                 <b>已有流水后不再改</b>：从「件」改成「斤」，历史数字一个不变而含义全变
     */
    String upsertItem(String entityNo, String skuNo, String name, String specText,
                      String barcode, String merchantSkuCode, String saleUnit);

    /**
     * 按平台 SKU 反查业主 —— <b>交易域手里只有 skuNo，没有主体号</b>。
     *
     * <p>走 {@code inv_item_ref}（{@code system=AISHOP}）反查：SKU 在平台内全局唯一，
     * 所以这一条是确定的。找不到说明这个 SKU 还没投影过来。
     *
     * @return 业主号；投影过来之前返回 {@code null}
     */
    String ownerOfSku(String skuNo);

    /** 按平台 SKU 反查物料。同 {@link #ownerOfSku} 走外部引用表。 */
    String itemIdOfSku(String skuNo);

    /**
     * 按业主 + 平台门店号取库位。{@code storeNo} 为空时给默认库位 ——
     * 那是存量「主体级库存」的落点。
     */
    String locationOfStore(String ownerId, String storeNo);
}
