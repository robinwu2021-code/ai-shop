package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** An original deduction can be restored at most once per purpose and never beyond its deducted quantity. */
@Getter
@Setter
@TableName("prd_stock_restore")
public class PrdStockRestore extends BaseEntity {
    private String purposeNo;
    private Long originalLockId;
    private Integer qty;
}
