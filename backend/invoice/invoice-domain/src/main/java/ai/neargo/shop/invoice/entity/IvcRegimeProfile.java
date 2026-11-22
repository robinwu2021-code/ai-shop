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
    /** 服务商侧的商家代号（V5，鲸跃 officeid） */
    private String providerAccountCode;
    /** secret 模块引用：服务商账号与密码（V5） */
    private Long providerSecretRef;
    /** 上传暂停原因（V6）；空 = 未暂停 */
    private String providerHoldReason;
    /** 暂停时间（V6） */
    private LocalDateTime providerHeldAt;
    /** 本期剩余号码提醒值（V9） */
    private Integer rangeLowWater;
    /** 余量不足 / 期末前自动向加值中心领号（V9） */
    private Boolean autoFetchRanges;
    /** 本期余量已报到的级别（V9），如 11512:LOW */
    private String rangeAlertKey;
    /** 已报过「下期号段未备」的期别（V9） */
    private String nextRangeAlertKey;
    /** ACTIVE / SUSPENDED */
    private String status;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
