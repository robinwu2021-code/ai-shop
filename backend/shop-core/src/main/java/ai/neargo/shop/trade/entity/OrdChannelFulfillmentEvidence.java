package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Append-only source proof committed atomically with the original successful physical handover. */
@Getter
@Setter
@TableName("ord_channel_fulfillment_evidence")
public class OrdChannelFulfillmentEvidence extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String sourceOrderRef;
    private String orderNo;
    private String subOrderNo;
    private String fulfillmentNo;
    private String revisionNo;
    private String receiptNo;
    private String stage;
    private String sourceHash;
    private String requestHash;
    private String planHash;
    private String inventoryReceiptHash;
    private String contentHash;
    private String payloadSnapshot;
    private LocalDateTime occurredAt;
}
