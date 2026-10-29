package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Original channel admission bookkeeping; collection evidence never marks a local cash payment. */
@Getter
@Setter
@TableName("ord_channel_preparation")
public class OrdChannelPreparation extends BaseEntity {
    private String preparationNo;
    private String entityNo;
    private String storeNo;
    private String admissionNo;
    private Long generation;
    private String reservationRef;
    private String status;
    private String planSnapshot;
    private String planHash;
    private String inventoryReceiptSnapshot;
    private String preparationReceiptRef;
    private LocalDateTime validUntil;
    private String previousPreparationNo;
}
