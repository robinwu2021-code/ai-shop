package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Verified callback persistence; raw receipt bytes are never exposed as a public entity. */
@Getter
@Setter
@TableName("cim_inbound_event")
public class CimInboundEvent extends BaseEntity {
    private String eventNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String connectionRevisionNo;
    private String dedupeKey;
    private String providerEventRef;
    private String eventType;
    private String payloadHash;
    private String originalReceiptNo;
    private LocalDateTime occurredAt;
    private String status;
    private Long attemptNo;
    private Long fence;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextAttemptAt;
    private String resultRef;
    private String externalOrderRef;
    private String externalRevision;
    private String authorizationNo;
    private String verificationEvidenceRef;
}
