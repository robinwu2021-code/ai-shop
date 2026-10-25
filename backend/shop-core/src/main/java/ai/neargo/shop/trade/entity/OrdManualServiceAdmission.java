package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Durable original merchant declaration evidence; never a Pay receipt or balance. */
@Getter
@Setter
@TableName("ord_manual_service_admission")
public class OrdManualServiceAdmission extends BaseEntity {
    private String declarationNo;
    private String operationNo;
    private String caller;
    private String orderNo;
    private String subOrderNo;
    private String entityNo;
    private String storeNo;
    private String buyerNo;
    private String admissionKind;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String accountNo;
    private String requestKeyHash;
    private String payloadHash;
    private String method;
    private Long declaredMinor;
    private String currency;
    private LocalDateTime receivedAt;
    private LocalDateTime recordedAt;
    private String note;
    private String receiptProvenance;
    private String phase;
    private Long admissionRevision;
    private Long orderRevision;
    private String quoteHash;
    private String stockAuthority;
    private Integer stockLifecycleVersion;
    private String stockManifestJson;
    private String stockManifestHash;
    private String stockOperationNo;
    private String stockOutcome;
    private String stockState;
    private String stockObservationHash;
    private String reasonCode;
    private LocalDateTime nextRetryAt;
}
