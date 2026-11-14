package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Genuine original full-return authorization and independent supplier reversal evidence. */
@Getter
@Setter
@TableName("cim_redemption_reversal")
public class CimRedemptionReversal extends BaseEntity {
    private String reversalNo;
    private String redemptionNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String sourceAuthorizationRef;
    private String sourceHash;
    private String certificateIdentityHash;
    private BigDecimal quantity;
    private String unit;
    private String operationNo;
    private String requestJson;
    private String status;
    private String originalReceiptRef;
    private String resultJson;
    private String sourceDecisionRef;
}
