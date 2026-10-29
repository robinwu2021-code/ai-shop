package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Original physical custody ledger, separate from SKU sellable inventory. */
@Getter
@Setter
@TableName("fnb_bottle_movement")
public class FnbBottleMovement extends BaseEntity {
    private String movementNo;
    private String entityNo;
    private String storeNo;
    private String custodyNo;
    private String action;
    private BigDecimal quantity;
    private BigDecimal remainingQty;
    private String unit;
    private String actorNo;
    private String recipientNo;
    private String reason;
    private String originalReceiptRef;
    private String keyHash;
    private String requestHash;
    private String resultSnapshot;
}
