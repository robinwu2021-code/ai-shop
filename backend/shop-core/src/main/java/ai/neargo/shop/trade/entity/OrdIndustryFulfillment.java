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
    /** 状态取值（零售域状态短值换值表）：只在这里写字面量，别处引用本常量。 */
    public static final String BUYER_CONFIRM_PENDING = ai.neargo.shop.spi.trade.IndustryFulfillmentPort.BUYER_CONFIRM_PENDING;
    public static final String HANDOVER_COMPLETE = ai.neargo.shop.spi.trade.IndustryFulfillmentPort.HANDOVER_COMPLETE;
    public static final String PROOF_VALIDATED = "PROOF_VALIDATED";
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
