package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 开票申请：**平台开给消费者**的销项票（ADR-017 §3.4 条件 2）。
 *
 * <p>与 {@code stl_purchase_invoice} 是两回事，别混：
 * 那张是<b>进项</b>（供应商开给平台，决定平台能不能列支成本），
 * 这张是<b>销项</b>（平台开给消费者，决定归集资金模式成不成立）。
 * 两者的义务人、方向、法律后果都相反。
 *
 * <p><b>本版是手工开票</b>：运营在 ops 开完票回填票号。
 * 条件 2 要的是「平台承担开票义务并实际履行」，不要求自动化 ——
 * 接票据系统是第二步，届时在 {@code ISSUED} 之后延长状态机，不改前面的。
 */
@Getter
@Setter
@TableName("ord_invoice_request")
public class OrdInvoiceRequest extends BaseEntity {

    public static final String REQUESTED = "REQUESTED";
    public static final String ISSUED = "ISSUED";
    public static final String REJECTED = "REJECTED";

    public static final String TITLE_PERSONAL = "PERSONAL";
    public static final String TITLE_COMPANY = "COMPANY";

    private String requestNo;

    /** 按**主单**申请，不按子单 —— 消费者眼里那是一次购买，票也该是一张 */
    private String orderNo;

    private String userNo;

    private String titleType;

    private String title;

    /** 单位抬头必填；个人抬头无此项 */
    private String taxNo;

    /** 电子票只能发到这里，填错就是开了也收不到 */
    private String email;

    /**
     * 开票金额**快照**。不实时读订单 ——
     * 后续退款会改订单金额，而已开出的票不会跟着变。
     */
    private Long amountMinor;

    private String status;

    private String invoiceNo;

    private Long issuedAt;

    /** 驳回原因。不写原因的驳回等于让消费者再猜一遍 */
    private String rejectReason;

    /** 经办人 —— 手工开票必须留痕，否则事后查不到是谁开的 */
    private String operatorNo;
}
