package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable local quantity effect or its evidence, not a second balance. */
@Getter
@Setter
@TableName("prd_stock_replenishment_return")
public class PrdStockReplenishmentReturn extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String originalNo;
    private String sourceSystem;
    private String sourceOwner;
    private String sourceDocument;
    private Integer sourceLine;
    private Integer acceptedQuantity;
    private String proofHash;
    private String sourceSnapshot;
}
