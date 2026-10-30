package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Original channel identities and import workflow facts; never exposed as public persistence
 * entities.
 */
@Getter
@Setter
@TableName("cim_order_link")
public class CimOrderLink extends BaseEntity {
    private String channelOrderNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String channelStoreKey;
    private String externalOrderRef;
    private LocalDateTime orderedAt;
    private String externalOrderKey;
    private String sourceOwner;
    private String connectionRevisionNo;
    private String activeRevisionNo;
    private String importRef;
    private String sourceOrderRef;
    private String coreOrderRef;
    private String importStatus;
    private String acceptanceStatus;
    private String platformStatus;
    private String fulfillmentStatus;
    private String financialEvidenceStatus;
    private String importSnapshot;
    private String preparationReceiptRef;
    private LocalDateTime preparationValidUntil;
    private String acceptanceReceiptRef;
    private String decisionOperationNo;
    private String platformAcceptanceReceiptRef;
    private Long attemptNo;
    private Long fence;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextAttemptAt;
}
