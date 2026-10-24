package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_recharge")
public class SvRecharge extends BaseEntity {
    private String issuerNo;
    private String issuerVersionNo;
    private String releaseNo;
    private String tierNo;
    private String collectionEntityNo;
    private String settlementEntityNo;
    private String quotaReservationNo;

    private String rechargeNo;
    private String requestId;
    private String payloadHash;
    private String sourceKind;
    private String planNo;
    private String planVersionNo;
    private String reservationNo;
    private String activityNo;
    private String activityVersionNo;
    private String userNo;
    private String customerNo;
    public String customerIdentity(){return customerNo==null?userNo:customerNo;}
    private String entityNo;
    private String storeNo;
    private String storeName;
    private String currency;
    private Long principalMinor;
    private Long bonusMinor;
    private String status;
    private String collectionMethod;
    private Long actualAmountMinor;
    private String receiptRef;
    private String receiptDigest;
    private String operatorNo;
    private String receivedBy;
    private LocalDateTime receivedAt;
    private LocalDateTime creditedAt;
    private LocalDateTime expiresAt;
    private String permitSnapshot;
    private String sourceSnapshot;
    private String receivedDecisionSnapshot;
    private String confirmationNote;
    private String receivedReceiptKey;
    private String cancellationReason;
    private LocalDateTime cancelledAt;
}
