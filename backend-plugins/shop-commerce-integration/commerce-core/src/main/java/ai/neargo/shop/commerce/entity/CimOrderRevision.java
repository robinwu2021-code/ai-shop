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
@TableName("cim_order_revision")
public class CimOrderRevision extends BaseEntity {
    private String revisionNo;
    private String entityNo;
    private String channelOrderNo;
    private String externalRevision;
    private Long externalSequence;
    private String sourceEventNo;
    private String sourceOperationNo;
    private String normalizedSnapshotRef;
    private String normalizedSnapshot;
    private String rawReceiptRef;
    private String originalReceiptRef;
    private String contentHash;
    private String catalogHash;
    private String currency;
    private LocalDateTime capturedAt;
}
