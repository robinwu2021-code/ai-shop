package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fnb_stock_movement")
public class FnbStockMovement extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String movementNo;
    private String commandNo;
    private String lineRef;
    private String itemNo;
    private String reservationNo;
    private String action;
    private String baseUnit;
    private String reasonCode;
    private String reasonDetail;
    private String originalMovementNo;
    private java.math.BigDecimal quantity;
    private java.math.BigDecimal onHandBefore;
    private java.math.BigDecimal onHandAfter;
    private java.math.BigDecimal reservedBefore;
    private java.math.BigDecimal reservedAfter;
}
