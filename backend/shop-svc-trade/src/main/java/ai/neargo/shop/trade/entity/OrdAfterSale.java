package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 售后单。**子单粒度**（Q6）：一次售后只针对一个商家 ——
 * 退款要退到那个商家的分账里去，跨商家的「一次退款」在资金上不存在。
 */
@Getter
@Setter
@TableName("ord_after_sale")
public class OrdAfterSale extends BaseEntity {

    public static final String APPLIED = "APPLIED";
    public static final String REFUNDING = "REFUNDING";
    public static final String REFUNDED = "REFUNDED";
    public static final String REJECTED = "REJECTED";
    public static final String ARBITRATING = "ARBITRATING";
    public static final String CLOSED = "CLOSED";

    public static final String REFUND_ONLY = "REFUND_ONLY";
    public static final String RETURN_REFUND = "RETURN_REFUND";
    public static final String EXCHANGE = "EXCHANGE";

    private String afterSaleNo;
    private String subOrderNo;
    private String orderNo;
    private String userNo;
    private String merchantNo;

    private String type;
    private String status;
    private String reason;

    /** JSON 数组：凭证图。 */
    private String images;

    private Long refundMinor;

    /** 极速退：命中阈值自动通过，**商家只可见不可拒**。 */
    private Boolean instant;

    /** 驳回理由：用户据此决定是否申诉，因此驳回时必填。 */
    private String merchantRemark;

    private String expressCompany;
    private String expressNo;

    /** PLATFORM / MERCHANT / PICKUP —— 平台裁决后才有（P-6.1.4，M4 口径未定）。 */
    private String liability;

    /** **退款前必须先回退分账**（E4），这一列是那条顺序的落点。 */
    private Boolean splitReversed;

    private Long refundedAt;
    /**
     * 上升平台时用户填的申诉理由。
     * 缺了它，平台裁决台只看得到商家的驳回理由 —— 单方面材料做不了裁决。
     */
    private String disputeReason;

}
