package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Permanent authentic original relationship; observation fields never authorize a new human
 * acceptance.
 */
@Getter
@Setter
@TableName("bty_service_execution_stock_intent")
public class BtyServiceExecutionStockIntent extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String workOrderNo;
    private String taskNo;
    private String fenceNo;
    private String submissionNo;
    private String executionNo;
    private String resourceCommandNo;
    private Integer attemptNo;
    private String bindingHash;
    private String sourceIntentHash;
    private String resourceCommandHash;
    private String planHash;
    private String referenceHash;
    private String lookupHash;
    private String retentionNo;
    private String requestHash;
    private String referenceJson;
    private String lookupJson;
    private String routeNo;
    private String routeHash;
    private String evidenceHash;
    private String receiptHash;
    private String evidenceJson;
    private String receiptJson;
    private LocalDateTime evidenceObservedAt;
    private LocalDateTime receiptObservedAt;
    private String observationState;
    private String pendingReason;
    private Long sourceFenceRevision;
    private Long observationEpoch;
}
