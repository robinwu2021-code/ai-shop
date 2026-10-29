package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("cim_after_sale_link")
public class CimAfterSaleLink extends BaseEntity {
    private String caseNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String channelOrderNo;
    private String externalCaseKey;
    private String externalCaseRef;
    private String activeRevisionNo;
    private String supplierState;
    private String type;
    private String currency;
    private String decisionStatus;
    private String sourceDecisionRef;
    private String sourceRevision;
    private String sourceDecisionHash;
    private String sourceDecisionSnapshot;
    private String localFundsStatus;
    private String localInventoryStatus;
    private String operationNo;
    private String payloadJson;
    private String connectionRevisionNo;
    private Long merchantRefundMinor;
    private LocalDateTime requestedAt;
}
