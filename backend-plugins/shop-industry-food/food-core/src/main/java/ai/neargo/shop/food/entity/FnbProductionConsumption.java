package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Durable production intent references the original inventory owner, never a FOOD stock ledger. */
@Getter
@Setter
@TableName("fnb_production_consumption")
public class FnbProductionConsumption extends BaseEntity {
    private String entityNo;
    private String consumptionNo;
    private String storeNo;
    private String sourceEventNo;
    private String purpose;
    private String requestHash;
    private String status;
    private String inventoryOperationRef;
    private String originalConsumptionNo;
    private String requestSnapshot;
    private String receiptSnapshot;
    private LocalDate businessDate;
    private LocalDateTime occurredAt;
}
