package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 主体开通的税制与开票通道（V1）。 */
@Getter
@Setter
@TableName("ivc_regime_profile")
public class IvcRegimeProfile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    /** TW_EGUI … */
    private String regimeCode;
    private String sellerTaxId;
    private String sellerName;
    private String gatewayCode;
    /** secret 模块密钥引用；明文 AES Key 不进业务表 */
    private Long qrKeyRef;
    private String memberCarrierType;
    /** 付款成功后自动开立 */
    private Boolean autoIssue;
    /** ACTIVE / SUSPENDED */
    private String status;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
