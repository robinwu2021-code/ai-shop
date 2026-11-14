package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Original controlled certificate reference and immutable provider/source evidence, not a local
 * asset balance.
 */
@Getter
@Setter
@TableName("cim_redemption_preview")
public class CimRedemptionPreview extends BaseEntity {
    private String previewNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String handleNo;
    private String businessJson;
    private String operationNo;
    private String requestJson;
    private String verificationJson;
    private String previewHash;
    private String sourceSkuRef;
    private String sourceAuthorizationRef;
    private String authorizationJson;
    private String status;
}
