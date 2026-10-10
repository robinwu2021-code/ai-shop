package ai.neargo.shop.spi.product;

/**
 * 门店货架的平台侧开关（TDD-运营端门店与商品治理 D3）。
 *
 * <p>门店强制下线要压的是<b>店级货架</b>（{@code prd_store_goods.on_sale}）并打上平台标记：
 * 处置记录要能区分「平台压下的」与「商家自己下架的」，解除时才只恢复前者。
 * 买家能看到什么在查询时按货架与门店状态现算（方案-商品可见性改查询时关联）。
 *
 * <p>放 Port 而不是让 merchant 域直连 {@code prd_*}：货架数据归 product 域，
 * 兄弟模块只能走契约（与 {@link ai.neargo.shop.spi.platform.AuditLogPort} 同理）。
 */
public interface StoreShelfPort {

    /**
     * 平台压下这家店的货架：把该店**当前在售**的商品行压为下架并打上
     * {@code platform_suspended} 标记，重算主体级总闸。
     *
     * <p>只压「当前在售」的行 —— 商家自己下架的东西不打标记，
     * 恢复时才不会替商家把它们重新上架。
     */
    void platformOffline(String entityNo, String storeNo);

    /**
     * 解除：只把带 {@code platform_suspended} 标记的行恢复为在售并清除标记，
     * 重算总闸。商家在处置期间的自主下架不受影响。
     */
    void platformRestore(String entityNo, String storeNo);
}
