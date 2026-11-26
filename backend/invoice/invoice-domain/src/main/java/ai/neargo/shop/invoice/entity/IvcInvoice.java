package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 已开立发票（V1）。金额、明细与证明联内容都是开立当下的冻结事实。 */
@Getter
@Setter
@TableName("ivc_invoice")
public class IvcInvoice {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String invoiceNo;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String regimeCode;
    private String channelCode;
    private String channelAccountNo;
    private Long channelAccountRevision;
    private Long channelRouteVersion;
    private String channelEnvironment;
    private String applicationNo;
    private Integer applicationRevisionNo;
    private String industryCode;
    private String sourceType;
    private String sourceNo;
    /** 作废后置空，唯一键保证同来源只有一张有效发票 */
    private String activeSourceKey;
    private String fiscalNumber;
    private String rangeNo;
    private String periodCode;
    private String randomCode;
    /** 开立当下的卖方税号，冻结 */
    private String sellerTaxId;
    private String sellerName;
    /** UTC */
    private LocalDateTime issuedAt;
    /** B2C / B2B */
    private String buyerType;
    /** 平台买家用户号（V3）；门店现场无账号的单为空 */
    private String buyerUserNo;
    private String buyerTaxId;
    private String buyerName;
    private String carrierType;
    private String carrierId;
    private String donateCode;
    /** Y / N */
    private String printMark;
    private String currency;
    private Long salesMinor;
    /** 零税率销售额 */
    // 新库的非空列不带默认值（旧表有）：初值写在实体上，插入时由代码给出。
    private Long zeroTaxSalesMinor = 0L;
    /** 免税销售额 */
    private Long freeTaxSalesMinor = 0L;
    private Long taxMinor;
    private Long totalMinor;
    private Long allowedMinor = 0L;
    private String taxType;
    private Integer taxRateBp;
    /** ISSUED / VOIDED / REVOKING 注销中 / REVOKED 已注销（V13） */
    private String status;
    /** 有效号码键（V13）：已注销置空，号码可同号重开；其余状态 = fiscalNumber */
    private String activeFiscalKey;
    private String revokeReason;
    private LocalDateTime revokeRequestedAt;
    private LocalDateTime revokedAt;
    private String revokeError;
    /** 0 未告警 / 1 注销超时已告警 / 2 期末未重开已告警 */
    private Integer revokeAlerted = 0;
    private String revisedFromInvoiceNo;
    private String revisedToInvoiceNo;
    /** 已注销未重开的号码期末作空白号码上报的时间（V13） */
    private LocalDateTime blankReportedAt;
    private String voidReason;
    private LocalDateTime voidedAt;
    private String voidedBy;
    private String proofSnapshot;
    private String proofSnapshotHash;
    /** 交易明细冻结内容（V4） */
    private String detailSnapshot;
    private String detailSnapshotHash;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 更新人 */
    @TableField(fill = FieldFill.INSERT)
    private String updatedBy;
}
