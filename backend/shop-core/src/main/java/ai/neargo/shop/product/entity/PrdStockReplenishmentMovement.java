package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable local quantity effect or its evidence, not a second balance. */
@Getter
@Setter
@TableName("prd_stock_replenishment_movement")
public class PrdStockReplenishmentMovement extends BaseEntity {
    private String movementNo;
    private String entityNo;
    private String storeNo;
    private String originalNo;
    private String commandAction;
    private String operationKey;
    private String kind;
    private String itemNo;
    private String baseUnit;
    private String targetIdentity;
    private String targetSnapshot;
    private Integer delta;
    private Integer onHandBefore;
    private Integer onHandAfter;
    private Integer reservedBefore;
    private Integer reservedAfter;
    private String reason;
}
