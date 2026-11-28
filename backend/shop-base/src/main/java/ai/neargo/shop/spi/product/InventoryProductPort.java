package ai.neargo.shop.spi.product;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存对接（invbridge）读写商品的唯一出口（TDD-商品领域迁到门店交易库 第 4 批）。
 *
 * <p>商品表搬进门店交易库后只由商品领域读写；库存对接此前直接用商品的实体与 Mapper，
 * 这里把它要的那几样收成只含字段的事实记录。库存同步、对齐、回填几项先保留原样，不走这里。
 */
public interface InventoryProductPort {

    /** 跟随类目 */
    String INV_INHERIT = "INHERIT";
    /** 这件货记库存 */
    String INV_ON = "ON";
    /** 这件货不记库存 */
    String INV_OFF = "OFF";
    /** 商品审核：待审 */
    String AUDIT_AUDITING = "AUDITING";
    /** 商品审核：已过审 */
    String AUDIT_APPROVED = "APPROVED";

    /** 商品的事实。{@code id} 只给需要按行号翻页或排序的跑批用 */
    record GoodsFact(Long id, String goodsNo, String entityNo, String storeNo, String categoryNo,
                     String title, String invMode, boolean onSale, String auditStatus) {
    }

    /** SKU 的事实 */
    record SkuFact(Long id, String skuNo, String goodsNo, String entityNo, String spec, String saleUnit,
                   int soldCount) {
    }

    /** 一个主体的商品数：建品、待审、上架 */
    record GoodsStats(long total, long auditing, long onSale) {
    }

    /** 单件商品（未删除），没有回 null */
    GoodsFact goods(String goodsNo);

    /** 这个主体的这件商品，没有回 null */
    GoodsFact goodsOf(String entityNo, String goodsNo);

    /** 这个主体的这批商品：goodsNo → 事实 */
    Map<String, GoodsFact> goodsOfBatch(String entityNo, Collection<String> goodsNos);

    /** 这个主体在架的商品 */
    List<GoodsFact> onSaleGoods(String entityNo);

    /** 这个主体记库存的商品 */
    List<GoodsFact> managedGoods(String entityNo);

    /** 改这个类目的开关会影响到的商品（跟随类目、且生效值与 {@code managed} 不同） */
    List<GoodsFact> goodsAffectedByCategory(String entityNo, String categoryNo, boolean managed);

    /** 全平台有主体、但不记库存的商品（启动回填用） */
    List<GoodsFact> unmanagedGoods();

    /** 这件货此刻记不记库存 */
    boolean isManaged(String entityNo, String goodsNo);

    /** 改单件商品的记库存方式（{@link #INV_INHERIT} / {@link #INV_ON} / {@link #INV_OFF}） */
    void saveGoodsMode(String entityNo, String goodsNo, String mode, String operator);

    /** 发「记库存方式变了」的事件 */
    void publishModeChanged(String entityNo, String goodsNo, boolean managed);

    /** 这批商品的 SKU（未删除） */
    List<SkuFact> skusOfGoods(Collection<String> goodsNos);

    /** 这个主体的这批 SKU */
    List<SkuFact> skus(String entityNo, Collection<String> skuNos);

    /** 这批 skuNo 里还在（未删除）的那些 */
    Set<String> aliveSkuNos(Collection<String> skuNos);

    /** 每个主体的商品数 */
    Map<String, GoodsStats> goodsStats(Set<String> entityNos);

    /** 这批锁号里哪些占着这个 SKU（发货守卫用，不连发件箱） */
    Set<String> lockNosHoldingSku(String skuNo, Collection<String> lockNos);
}
