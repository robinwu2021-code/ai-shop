package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店级库存。
 *
 * <p><b>它是可选的覆盖层，不是 {@code prd_sku.stock} 的替代</b>：
 * <ul>
 *   <li>某 SKU 一条店级行都没有 → 走主体总量，行为与单店时代逐字相同
 *   <li>一旦有了任意一条 → 该 SKU 整体转为店级管理，**没有行的店视为 0**
 * </ul>
 *
 * <p>最后半句是这个模型的关键。若改成「没有行就回退总量」，商家给 A 店设了 10 件后
 * B 店会变成无限库存 —— 比不分店更危险。
 */
@Getter
@Setter
@TableName("prd_store_stock")
public class PrdStoreStock extends BaseEntity {

    private String storeNo;
    private String skuNo;
    /** 冗余主体号：按商家查全部门店库存时免 join */
    private String entityNo;
    private Integer stock;
    private Integer lockedStock;
}
