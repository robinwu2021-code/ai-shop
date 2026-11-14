package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Durable stock admission metadata; never an additional stock balance. */
@Getter
@Setter
@TableName("prd_stock_replenishment")
public class PrdStockReplenishment extends BaseEntity {
    private String originalNo;
    private String entityNo;
    private String storeNo;
    private String targetOwner;
    private String sourceSystem;
    private String sourceOwner;
    private String sourceDocument;
    private Integer sourceLine;
    private String bindingHash;
    private String sourceSnapshot;
    private String mappingSnapshot;
    private String targetSnapshot;
    private Long admissionGeneration;
    private BigDecimal expectedQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal differenceQuantity;
    private BigDecimal returnedQuantity;
    private BigDecimal returnAcceptedQuantity;
    private String status;
    private String closeReason;
}
