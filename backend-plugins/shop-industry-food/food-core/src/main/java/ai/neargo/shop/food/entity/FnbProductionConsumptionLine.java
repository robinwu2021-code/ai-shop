package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Durable production intent references the original inventory owner, never a FOOD stock ledger. */
@Getter
@Setter
@TableName("fnb_production_consumption_line")
public class FnbProductionConsumptionLine extends BaseEntity {
    private String entityNo;
    private String consumptionNo;
    private String componentPath;
    private String itemRef;
    private String unit;
    private String recipeRevisionNo;
    private String inventoryReceiptRef;
    private String currency;
    private String status;
    private Integer lineNo;
    private BigDecimal theoryQty;
    private BigDecimal actualQty;
    private Long costMinor;
}
