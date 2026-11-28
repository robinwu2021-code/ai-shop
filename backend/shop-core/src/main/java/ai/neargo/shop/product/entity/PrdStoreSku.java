package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店 SKU（门店交易库 {@code prd_store_sku}）：这家店对某个 SKU 的门店价与门店库存，一店一 SKU 一行。
 *
 * <p>由旧库的 {@code prd_store_price} 与 {@code prd_store_stock} 并成。两半<b>各自可空、回退方向相反</b>：
 * <ul>
 *   <li><b>门店价</b>（{@link #price}）：空 = 这家店没单独定价，<b>回退主体价</b>（fail-back）。
 *       视为 0 就是白送 —— 一家没配过价的店把所有货以 0 元卖出去。</li>
 *   <li><b>门店库存</b>（{@link #sellableQty}）：空 = 这家店没有店级库存。某 SKU 只要有任何一家店的
 *       {@code sellable_qty} 非空，该 SKU 就整体转为按店管理，<b>没有店级库存的店视为 0</b>（fail-closed）；
 *       一家都没有时走主体总量。</li>
 * </ul>
 * 所以判断「有没有门店价 / 店级库存」一律看列是否为空，<b>不看行在不在</b> —— 只设过价的店也有一行。
 *
 * <p>库存口径沿用旧表：{@link #sellableQty} 是总量（含已锁），可卖 = {@code sellable_qty - locked_qty}。
 */
@Getter
@Setter
@TableName("prd_store_sku")
public class PrdStoreSku extends BaseEntity {
    /** The entity-wide fallback pool has a unique, non-null key even on MySQL. */
    public static final String ENTITY_POOL = "";

    private String storeNo;
    private String skuNo;
    /** 冗余主体号：按商家查全部门店时免 join，也是数据域锚点 */
    private String entityNo;
    /** 市场码，与 {@code prd_sku.market} 同一套 */
    private String market;
    /** 这家店的售价（最小货币单位）；空 = 回退主体价 */
    private Long price;
    /** 这家店的划线价；空 = 用主体的划线价 */
    private Long originPrice;
    /** 这家店是否在售：1 在售 / 0 停售；空 = 未单独设置 */
    private Integer onSale;
    /** 店级库存总量（含已锁）；空 = 这家店没有店级库存 */
    private Integer sellableQty;
    /** 已被下单锁住、未支付的量 */
    private Integer lockedQty;
}
