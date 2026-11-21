package ai.neargo.shop.invoice.entity;

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
    private Long zeroTaxSalesMinor;
    /** 免税销售额 */
    private Long freeTaxSalesMinor;
    private Long taxMinor;
    private Long totalMinor;
    private Long allowedMinor;
    private String taxType;
    private Integer taxRateBp;
    /** ISSUED / VOIDED */
    private String status;
    private String voidReason;
    private LocalDateTime voidedAt;
    private String voidedBy;
    private String proofSnapshot;
    private String proofSnapshotHash;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
