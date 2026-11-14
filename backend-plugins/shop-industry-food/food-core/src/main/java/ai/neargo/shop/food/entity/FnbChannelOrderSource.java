package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Original external restaurant relationship; it grants neither consumer-account access nor local
 * payment authority.
 */
@Getter
@Setter
@TableName("fnb_channel_order_source")
public class FnbChannelOrderSource extends BaseEntity {
    private String sourceNo;
    private String entityNo;
    private String storeNo;
    private String importNo;
    private String channelStoreKey;
    private String externalOrderKey;
    private String externalOrderRef;
    private String originalDocumentHash;
    private String originalFinancialHash;
    private String connectionNo;
    private String connectionRevisionNo;
    private String externalSnapshotNo;
    private String partyNo;
    private String cartNo;
    private String batchNo;
    private Long sourceVersion;
    private String sourceHash;
    private String sourceSnapshot;
    private String envelopeSnapshot;
    private String status;
    private String coreOrderNo;
    private String coreSubOrderNo;
    private String coreAcceptanceReceiptRef;
    private String corePreparationSnapshot;
    private String coreAcceptanceSnapshot;
    private String acceptanceReceiptRef;
    private String cancellationReceiptRef;
    private Long fence;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextRecoveryAt;
    private String lastErrorCode;
}
