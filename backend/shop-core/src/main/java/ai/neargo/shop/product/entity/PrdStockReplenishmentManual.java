package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable local quantity effect or its evidence, not a second balance. */
@Getter
@Setter
@TableName("prd_stock_replenishment_manual")
public class PrdStockReplenishmentManual extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String originalNo;
    private String movementNo;
    private Integer quantity;
}
