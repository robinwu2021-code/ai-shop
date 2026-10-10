package ai.neargo.shop.trade.entity;

import java.util.List;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /*
     * ── 售后原因（`reason` 列）──────────────────────────────────────────
     *
     * 收编到实体上，与上面两组取值域同处。此前它是 AfterSaleServiceImpl 里的一个
     * 裸 List.of，而端上有具名类型 `shared:AfterSaleReason` —— 两侧曾经真的漂过：
     * 后端原是中文字面量，c-app 压根没调那个接口、自己硬编码了另一份**六个码**的清单。
     * 修好之后一直没有护栏，因为**裸 List 进不了按字段对账**
     * （scripts/check-enum-fields.mjs 读的是 `static final String`）。
     * 现在它登记成了 `ord_after_sale.reason`，两侧不等就红。
     *
     * 下发的是码不是文案：这是三语 App（zh/en/ar），下发中文等于把翻译从端上剥夺掉。
     */
    public static final String REASON_NOT_WANTED = "NOT_WANTED";
    public static final String REASON_DAMAGED = "DAMAGED";
    public static final String REASON_MISSING = "MISSING";
    public static final String REASON_WRONG_ITEM = "WRONG_ITEM";
    public static final String REASON_QUALITY = "QUALITY";
    public static final String REASON_EXPIRED = "EXPIRED";
    public static final String REASON_OTHER = "OTHER";

    /** 下发给端上的原因清单。顺序即展示顺序，`OTHER` 压在最后。 */
    public static final List<String> REASONS = List.of(
            REASON_NOT_WANTED, REASON_DAMAGED, REASON_MISSING, REASON_WRONG_ITEM,
            REASON_QUALITY, REASON_EXPIRED, REASON_OTHER);

    private String afterSaleNo;
    private String subOrderNo;
    private String orderNo;
    private String userNo;
    private String entityNo;

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


    /**
     * 退款时积分扣不回来的部分，折成现金从退款里扣（分）。
     *
     * <p><b>退款单必须明示</b>：「已使用积分优惠 1.00 元，本次退款 99.00 元」。
     * 不写清楚，「我退 100 你只退我 99」必然变成客诉。
     */
    private Long pointsOffsetMinor;

    /** 对应的退款流水号（stl_payment.payment_no）。退款要重试，重试要幂等，幂等靠它。 */
    private String refundPaymentNo;
    /**
     * 已回补过库存（V256）。**只有 {@code RETURN_REFUND} 会置位**。
     *
     * <p>与回补动作在同一个事务里写：回补成功而状态没落库时，重试据此跳过 ——
     * 否则会多补一次，而多出来的那几件不会有任何地方报错。
     */
    private Integer stockRestored;
}
