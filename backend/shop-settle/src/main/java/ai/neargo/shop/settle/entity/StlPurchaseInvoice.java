package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 采购进项票（自营）。**供应商开给平台**。
 *
 * <p>与销项票（平台/商家开给消费者）不是一回事：那三条端点解决的是消费者报销，
 * 这张表解决的是<b>平台能不能把采购成本税前列支</b>。
 *
 * <p><b>为什么必须有</b>：自营模式下平台是销售主体，付给供应商的钱没有发票就不能列支。
 * 「付得出去」和「能入账」是两件事——对私转账银行不拦，但没有发票这笔支出在税上不存在，
 * 平台按全额确认收入却零成本，利润凭空变大，税跟着变大。
 */
@Getter
@Setter
@TableName("stl_purchase_invoice")
public class StlPurchaseInvoice extends BaseEntity {

    /** 待核验。 */
    public static final String SUBMITTED = "SUBMITTED";
    /** 已核验，可付款。 */
    public static final String VERIFIED = "VERIFIED";
    /** 已驳回，供应商需重新提交。 */
    public static final String REJECTED = "REJECTED";

    /** 普票：解决企业所得税（成本可列支），**不能抵扣增值税进项**。 */
    public static final String GENERAL = "GENERAL";
    /** 专票：既可列支成本，又能抵扣进项。 */
    public static final String SPECIAL = "SPECIAL";

    private String invoiceNo;
    private String entityNo;

    /** 覆盖的结算周期，如 {@code 2026-08}。一张票覆盖该周期的多张单。 */
    private String period;

    private String invoiceCode;
    private String invoiceNumber;

    /** {@link #GENERAL} / {@link #SPECIAL}。 */
    private String invoiceType;

    /**
     * 开票方名称。
     *
     * <p><b>三流一致的比对锚点</b>：税务上要求合同流、资金流、发票流指向同一主体，
     * 也就是「供应商主体名 = 开票方名称 = 结算账户户名」。不一致会被认定为虚开风险，
     * 后果远大于少抵一点税。常见的不一致是「个体户用法人个人名义开票」——
     * 肉眼很容易放过，所以要机器比对。
     */
    private String titleName;

    private String titleTaxNo;
    private Long amountMinor;
    private Long taxAmountMinor;
    private Integer taxRate;
    private Long invoiceDate;
    private String imageUrl;
    private String status;
    private String verifiedBy;
    private Long verifiedAt;

    /** 驳回原因。**必填**——供应商得知道是抬头错了、金额不符还是影像看不清，否则只能反复试。 */
    private String rejectReason;
}
