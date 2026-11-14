package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Verified callback persistence; raw receipt bytes are never exposed as a public entity. */
@Getter
@Setter
@TableName("cim_inbound_receipt")
public class CimInboundReceipt extends BaseEntity {
    private String receiptNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String eventNo;
    private LocalDateTime receivedAt;
    private String payloadRef;
    private String payloadHash;
    private String signatureResult;
    private String classification;
    private LocalDateTime retentionUntil;
    private String payloadBase64;
    private String authorizationNo;
    private String verificationEvidenceRef;
}
