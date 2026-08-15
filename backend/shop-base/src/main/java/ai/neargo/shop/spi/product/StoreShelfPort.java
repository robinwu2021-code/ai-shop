package ai.neargo.shop.spi.product;

/**
 * 门店货架的平台侧开关（TDD-运营端门店与商品治理 D3）。
 *
 * <p>门店强制下线**不能只改 {@code mch_store.status}**：C 端可见性的真闸门是
 * {@code prd_goods.on_sale} × {@code prd_store_goods.on_sale} × {@code prd_community_pool}，
 * 门店状态在那条链路上没有任何读者。只改状态的结果是「处置完了还在卖」——
 * 比不处置更糟，因为运营以为已经拦住了。
 *
 * <p>放 Port 而不是让 merchant 域直连 {@code prd_*}：货架数据归 product 域，
 * 兄弟模块只能走契约（与 {@link ai.neargo.shop.spi.platform.AuditLogPort} 同理）。
 */
public interface StoreShelfPort {

    /**
     * 平台压下这家店的货架：把该店**当前在售**的商品行压为下架并打上
     * {@code platform_suspended} 标记，重算主体级总闸，同步社区池。
     *
     * <p>只压「当前在售」的行 —— 商家自己下架的东西不打标记，
     * 恢复时才不会替商家把它们重新上架。
     */
    void platformOffline(String entityNo, String storeNo);

    /**
     * 解除：只把带 {@code platform_suspended} 标记的行恢复为在售并清除标记，
     * 重算总闸、回同步社区池。商家在处置期间的自主下架不受影响。
     */
    void platformRestore(String entityNo, String storeNo);
}
