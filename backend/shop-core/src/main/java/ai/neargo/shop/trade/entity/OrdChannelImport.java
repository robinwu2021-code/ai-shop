package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Original channel admission bookkeeping; collection evidence never marks a local cash payment. */
@Getter
@Setter
@TableName("ord_channel_import")
public class OrdChannelImport extends BaseEntity {
    private String admissionNo;
    private String entityNo;
    private String storeNo;
    private String channelStoreKey;
    private String externalOrderKey;
    private String externalOrderRef;
    private Long fence;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String sourceSystem;
    private String sourceNo;
    private Long sourceVersion;
    private String sourceHash;
    private String importRef;
    private String quoteNo;
    private String orderNo;
    private String subOrderNo;
    private String status;
    private String fundingStatus;
    private String policyNo;
    private String policySnapshot;
    private Long reservedMinor;
    private String preparationNo;
    private String acceptanceReceiptRef;
    private String platformAcceptanceSnapshot;
    private String cancellationReceiptRef;
    private String allocationsJson;
}
