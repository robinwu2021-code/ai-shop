package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Original controlled certificate reference and immutable provider/source evidence, not a local
 * asset balance.
 */
@Getter
@Setter
@TableName("cim_redemption_link")
public class CimRedemptionLink extends BaseEntity {
    private String redemptionNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String previewNo;
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
