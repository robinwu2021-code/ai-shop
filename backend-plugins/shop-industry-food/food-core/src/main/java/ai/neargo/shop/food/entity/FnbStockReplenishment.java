package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter @TableName("fnb_stock_replenishment")
public class FnbStockReplenishment extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String replenishmentNo;
    private String sourceSystem;
    private String sourceOwner;
    private String sourceDocument;
    private Integer sourceLine;
    private String sourceItem;
    private String sourceUnit;
    private BigDecimal sourceQuantity;
    private String itemNo;
    private String baseUnit;
    private BigDecimal factor;
    private BigDecimal expectedQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal differenceQuantity;
    private BigDecimal returnedQuantity;
    private BigDecimal returnAcceptedQuantity;
    private String status;
    private String closeReason;
    private Long admissionGeneration;
    private String admissionMode;
    private Long sourcePostingSequence;
}
