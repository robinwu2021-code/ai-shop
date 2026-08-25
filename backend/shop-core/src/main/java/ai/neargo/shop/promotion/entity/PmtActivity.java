package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 活动 = 触发 × 优惠 × 排期 × 限量。
 *
 * <p>老模型（{@code mkt_campaign}）用一个 {@code type} 表达四类玩法，于是
 * 「第二件半价」= QTY × PRICE、「满额送券」= AMOUNT × COUPON 这类组合<b>加不进去</b> ——
 * 每加一种就要改一次算价。拆开之后新玩法是新组合，不是新枚举。
 */
@Getter
@Setter
@TableName("pmt_activity")
public class PmtActivity extends BaseEntity {

    /** 无条件（发券型活动） */
    public static final String TRIGGER_NONE = "NONE";
    /** 订单满额 */
    public static final String TRIGGER_AMOUNT = "AMOUNT";
    /** 买够件数 */
    public static final String TRIGGER_QTY = "QTY";
    /** 命中商品 */
    public static final String TRIGGER_GOODS = "GOODS";

    /** 减金额 */
    public static final String BENEFIT_CUT = "CUT";
    /** 改单价（限时特价） */
    public static final String BENEFIT_PRICE = "PRICE";
    /** 送商品 */
    public static final String BENEFIT_GIFT = "GIFT";
    /** 发券 */
    public static final String BENEFIT_COUPON = "COUPON";

    /** 短期：有起有止 */
    public static final String ONE_OFF = "ONE_OFF";
    /** 长期：没有结束时间 —— <b>因此必须有限量或预算</b>，否则是永久敞口 */
    public static final String ALWAYS_ON = "ALWAYS_ON";
    /** 周期：每周三、每天某几个小时 */
    public static final String RECURRING = "RECURRING";

    public static final String DRAFT = "DRAFT";
    public static final String RUNNING = "RUNNING";
    public static final String PAUSED = "PAUSED";
    public static final String ENDED = "ENDED";

    /** 到期 */
    public static final String ENDED_EXPIRED = "EXPIRED";
    /** 到量 */
    public static final String ENDED_QUOTA = "QUOTA";
    /** 预算用尽 */
    public static final String ENDED_BUDGET = "BUDGET";
    /** 商家手动停的 */
    public static final String ENDED_MANUAL = "MANUAL";

    private String activityNo;
    private String entityNo;
    private String storeNo;
    private String name;
    private String goal;

    private String triggerType;
    private Long triggerAmountMinor;
    private Integer triggerQty;

    private String benefitType;
    private Long benefitAmountMinor;
    private Integer benefitQty;
    private String benefitRef;

    private String scheduleType;
    private Long startAt;
    private Long endAt;
    private String scheduleRule;

    private Integer quota;
    private Integer quotaUsed;
    private Long budgetMinor;
    private Long budgetUsedMinor;

    private String status;
    /** 商家问「怎么停了」要有答案 —— 停了但说不出为什么，他会以为是系统坏了 */
    private String endedReason;
    private java.time.LocalDateTime archivedAt;

    /**
     * 此刻生不生效。<b>排期判断只有这一处</b>。
     *
     * <p>三种排期都收敛到这里：短期看起止、长期只看状态、周期还要看今天是周几、
     * 现在是不是在那个时段里。散在各处的话，「为什么活动没生效」会有三份不同的答案。
     *
     * <p>不判受众、不判限量 —— 那是另外两件事（受众看人，限量看已用）。
     * 混在一起会让「他不在受众里」和「活动已经过期」共用一条排查路径。
     */
    public boolean isActiveAt(long now, java.time.ZoneId zone) {
        if (!RUNNING.equals(status)) {
            return false;
        }
        if (startAt != null && startAt > now) {
            return false;
        }
        if (endAt != null && endAt < now) {
            return false;
        }
        if (!RECURRING.equals(scheduleType)) {
            return true;
        }
        return RecurringRule.parse(scheduleRule).matches(now, zone);
    }

    /** 还有没有量。限量为空 = 不限 */
    public boolean hasQuotaLeft() {
        return quota == null || nz(quotaUsed) < quota;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
