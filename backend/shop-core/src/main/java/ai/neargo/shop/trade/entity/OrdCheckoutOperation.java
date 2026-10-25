package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_checkout_operation")
public class OrdCheckoutOperation extends BaseEntity {
    /** Immutable owner-selected purchase lane; legacy requests cannot select manual mode. */
    private String admissionKind;

    private Long manualObservationRevision;
    private String manualObservationHash;

    private String operationNo;
    private String caller;
    private String entityNo;
    private String storeNo;
    private String buyerNo;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String requestKeyHash;
    private String payloadHash;
    private Long scopeRevision;
    private String scopeHash;
    private String commandJson;
    private String phase;
    private String currentQuoteNo;
    private String confirmationNo;
    private String placeKeyHash;
    private String placePayloadHash;
    private String orderNo;
    private String subOrderNo;
    private Long sourceRevision;
    private String sourceObservationHash;
    private String lastErrorCode;
    private LocalDateTime nextRetryAt;
}
