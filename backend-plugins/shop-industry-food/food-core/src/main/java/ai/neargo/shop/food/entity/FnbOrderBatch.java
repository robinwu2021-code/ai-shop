package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Original restaurant transaction table; monetary authority remains in core and PAY. */
@Getter
@Setter
@TableName("fnb_order_batch")
public class FnbOrderBatch extends BaseEntity {
    private LocalDateTime nextRecoveryAt;
    private String entityNo;
    private String batchNo;
    private String storeNo;
    private String checkNo;
    private String requestKey;
    private String requestHash;
    private String source;
    private String buyerNo;
    private String partyNo;
    private String operatorNo;
    private String status;
    private String frozenRequest;
    private LocalDateTime acceptedAt;
    private String cartNo;
    private Long cartVersion;
    private String quoteRef;
    private String quoteHash;
    private LocalDate businessDate;
    private String policyNo;
    private String fulfillment;
    private String payMode;
    private LocalDateTime scheduledAt;
    private String actorRealm;
    private String payerRef;
    private String quotaClaimRefs;
}
