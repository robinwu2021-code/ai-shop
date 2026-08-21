package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店级售价。
 *
 * <p><b>回退方向与 {@link PrdStoreStock} 相反 —— 这是两者唯一不同构的地方：</b>
 * <ul>
 *   <li>门店库存：没有行的店<b>视为 0</b>（fail-closed）。回退主体总量的话，
 *       商家给 A 店设了 10 件之后 B 店会变成无限供应</li>
 *   <li>门店价格：没有行的店<b>回退主体价</b>（fail-back）。视为 0 就是白送 ——
 *       一家没配过价的店把所有货以 ¥0.00 卖出去，页面上看着像 bug，钱已经出去了</li>
 * </ul>
 *
 * <p>叠加顺序：门店价是<b>基准价</b>，限时特价仍然覆盖它。
 */
@Getter
@Setter
@TableName("prd_store_price")
public class PrdStorePrice extends BaseEntity {

    private String storeNo;
    private String skuNo;
    /** 冗余主体号：按商家查全部门店价时免 join，也是数据域锚点 */
    private String entityNo;
    /** 市场码，与 {@code prd_sku.market} 同一套。一个 SKU 在三个市场是三行价 */
    private String market;
    private Long price;
    /** 这家店的划线价。空 = 用主体的划线价 */
    private Long originPrice;
}
