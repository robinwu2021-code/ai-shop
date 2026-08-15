package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家结算发票申请（矩阵 P-12.2.4 结算凭证/发票）。
 *
 * <p><b>这是第三个方向的票，前两个都已经有表了，别合并</b>：
 * <ul>
 *   <li>{@link StlPurchaseInvoice} 供应商 → 平台（<b>进项</b>，自营应付）</li>
 *   <li>{@code ord_invoice_request} 平台 → 消费者（<b>销项</b>，C 端申请，V94）</li>
 *   <li>本表 平台 → 商家（商家拿结算款要的凭证）</li>
 * </ul>
 * 三张票的开票方、受票方、金额来源、税务后果各不相同。合成一张表的代价是
 * 「谁欠谁」这件事再也说不清 —— 而那正是发票唯一要回答的问题。
 */
@Getter
@Setter
@TableName("stl_settle_invoice")
public class StlSettleInvoice extends BaseEntity {

    /** 已申请，等运营开。 */
    public static final String PENDING = "PENDING";
    /** 已开具（回填流水号）。 */
    public static final String ISSUED = "ISSUED";
    /** 驳回，必须写原因。 */
    public static final String REJECTED = "REJECTED";

    /** 企业抬头。<b>必须有税号</b>。 */
    public static final String COMPANY = "COMPANY";
    /** 个人抬头。没有税号，走另一条校验路径。 */
    public static final String PERSONAL = "PERSONAL";

    private String invoiceNo;

    /** 申请商家。 */
    private String entityNo;

    private String merchantName;

    /** 开票周期 {@code YYYY-MM}，与结算周期同口径。 */
    private String period;

    private Long amountMinor;

    /**
     * 该周期已结算金额快照（分）。<b>开票金额不得超过它</b> ——
     * 超出部分没有真实交易对应，就是虚开。
     *
     * <p>落快照是因为后续退款会改结算额，而<b>已开的票不会跟着变</b>。
     */
    private Long settledAmountMinor;

    /** {@link #COMPANY} / {@link #PERSONAL}。 */
    private String titleType;

    private String title;

    /** 纳税人识别号。企业抬头必填。 */
    private String taxNo;

    private String status;

    /** 发票流水号，开完回填。 */
    private String serialNo;

    private Long appliedAt;
    private Long decidedAt;

    /** 经办人 —— 手工开票必须留痕，否则事后查不到是谁开的。 */
    private String decidedBy;

    /** 驳回原因。原样回商家 B 端。 */
    private String remark;
}
