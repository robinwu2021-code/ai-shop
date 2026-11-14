package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Original-source fulfillment metadata; no payment state or inventory ledger is duplicated. */
@Getter
@Setter
@TableName("ord_industry_fulfillment")
public class OrdIndustryFulfillment extends BaseEntity {
    private String fulfillmentNo;
    private String entityNo;
    private String storeNo;
    private String orderNo;
    private String subOrderNo;
    private String sourceSystem;
    private String sourceType;
    private String sourceNo;
    private Long sourceVersion;
    private String sourceHash;
    private String actorNo;
    private String operationKey;
    private String requestHash;
    private String verifyCodeHash;
    private Long expectedSubVersion;
    private String status;
    private String inventoryReceiptJson;
    private String errorCode;
    private String fulfillmentKind;
    private String planHash;
    private String commandSnapshot;
}
