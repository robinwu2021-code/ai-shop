package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Original external-certificate consumption decision, independent from local assets and payment
 * status.
 */
@Getter
@Setter
@TableName("ord_external_certificate_use")
public class OrdExternalCertificateUse extends BaseEntity {
    private String authorizationNo;
    private String originalAuthorizationNo;
    private String purpose;
    private String reversalRef;
    private String entityNo;
    private String storeNo;
    private String subOrderNo;
    private Long orderItemId;
    private String previewRef;
    private String previewHash;
    private String certificateIdentityHash;
    private String sourceHash;
    private String sourceSnapshot;
    private BigDecimal quantity;
    private String unit;
    private String status;
    private LocalDateTime expiresAt;
    private String decisionRef;
    private String originalReceiptRef;
}
