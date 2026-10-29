package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Durable process evidence; amounts remain in the original payment ledger. */
@Getter
@Setter
@TableName("stl_payment_operation")
public class StlPaymentOperation extends BaseEntity {
    private String operationNo;
    private String canonicalOperationNo;
    private String ownerKind;
    private String orderNo;
    private String buyerNo;
    private String entityNo;
    private String storeNo;
    private String requestHash;
    private String paymentRequestJson;
    private String offlineRequestJson;
    private String purpose;
    private String phase;
    private String paymentNo;
    private String outTradeNo;
    private LocalDateTime sendStartedAt;
    private String payParamsJson;
    private LocalDateTime payParamsExpiresAt;
    private String lastErrorCode;
    private String method;
    private String evidenceRef;
    private String evidenceJson;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String actorAccountNo;
    private LocalDateTime receivedAt;
    private String note;
}
