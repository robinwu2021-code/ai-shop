package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 券模板 = 权益 × 门槛 × 范围 × 有效期 × 发放 × 核销 × 次数。
 *
 * <p>老模型（{@code mkt_coupon}）把这七件事压在 {@code type} 一列上，
 * 于是「凭券到店领一个鸡蛋」和「满 100 减 20」只能用同一套字段表达 ——
 * 加一种玩法就要改一次算价。这里拆成正交的几段，加玩法只是多一个取值。
 */
@Getter
@Setter
@TableName("pmt_coupon")
public class PmtCoupon extends BaseEntity {

    /** 现金：{@code benefitValue} 是面额（分） */
    public static final String CASH = "CASH";
    /** 折扣：{@code benefitValue} 是<b>万分比</b>，8500 = 八五折 */
    public static final String PERCENT = "PERCENT";
    /** 兑换：{@code benefitRef} 是兑换品。优惠金额记 0 —— 它减的不是钱 */
    public static final String GIFT = "GIFT";
    public static final String FREE_SHIP = "FREE_SHIP";

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_STORE = "STORE";
    public static final String SCOPE_CATEGORY = "CATEGORY";
    public static final String SCOPE_GOODS = "GOODS";

    public static final String ABSOLUTE = "ABSOLUTE";
    /** 领取后 N 天有效 —— 唤回券的标准形态（「给你 3 天，别再放着」） */
    public static final String RELATIVE = "RELATIVE";

    public static final String ISSUE_CENTER = "CENTER";
    public static final String ISSUE_TARGETED = "TARGETED";
    public static final String ISSUE_ACTIVITY = "ACTIVITY";

    /** 下单自动抵扣 */
    public static final String REDEEM_ORDER = "ORDER";
    /** 到店出示核销。<b>不参与下单算价</b> —— 一张券两条路一定会被用两次 */
    public static final String REDEEM_STORE_CODE = "STORE_CODE";

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String ENDED = "ENDED";

    public static final String BY_PLATFORM = "PLATFORM";
    public static final String BY_MERCHANT = "MERCHANT";

    private String couponNo;
    private String entityNo;
    private String funder;
    private String title;

    private String benefitMode;
    private Long benefitValue;
    private Long benefitCapMinor;
    private String benefitRef;

    private Long minAmountMinor;
    private Integer minQty;

    private String scopeType;
    /** 展示文案。<b>规则以 {@code pmt_coupon_scope} 为准</b> */
    private String scopeDesc;

    private String validityMode;
    private Long startAt;
    private Long endAt;
    private Integer validDays;

    private String issueMode;
    private String redeemMode;
    private Integer timesTotal;

    private Integer totalCount;
    private Integer receivedCount;
    private Integer perUserLimit;
    private Long budgetMinor;

    private String status;
    private java.time.LocalDateTime archivedAt;

    /**
     * 这张券对 {@code base} 分的商品能减多少（分）。<b>算优惠只有这一处</b>。
     *
     * <p>与老模型 {@code MktCoupon#discountFor} 逐分一致 ——
     * {@code CouponModelCompatTest} 拿同一批券对着跑两边，差一分就红。
     * 改这里之前先想清楚：存量券的历史账是按这个算出来的。
     *
     * @param base 参与计算的商品额（分）。商家券只算本店那部分
     */
    public long discountFor(long base) {
        if (PERCENT.equals(benefitMode)) {
            long rate = benefitValue == null ? 0 : benefitValue;
            long off = base * (10_000 - rate) / 10_000;
            long cap = benefitCapMinor == null ? 0 : benefitCapMinor;
            return cap > 0 ? Math.min(off, cap) : off;
        }
        if (CASH.equals(benefitMode)) {
            // 满减不能减成负数：券面额大于商品额时按商品额封顶
            return Math.min(benefitValue == null ? 0 : benefitValue, base);
        }
        // 兑换与免运费不在这里减钱：前者给的是东西，后者减的是运费（在运费那一步）
        return 0L;
    }

    /** 一张能用几次。次卡（豆浆 5 杯）就是这一列 &gt; 1 */
    public int timesTotalOrOne() {
        return timesTotal == null || timesTotal < 1 ? 1 : timesTotal;
    }
}
