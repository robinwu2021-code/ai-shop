package ai.neargo.shop.marketing.coupon.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 券模板。
 *
 * <p><b>{@link #funder} 是本表最重要的一列</b>：平台券的钱平台出、商家足额收款；
 * 商家券的钱商家自己出、分账时扣减。没有它，M7 分账无法判断该扣谁的钱（Q9）。
 */
@Getter
@Setter
@TableName("mkt_coupon")
public class MktCoupon extends BaseEntity {

    /** 可领可用。 */
    public static final String ACTIVE = "ACTIVE";
    /**
     * 平台暂停（P-7.1）。
     *
     * <p>暂停即刻生效，不用改别的地方：{@code center()} 与 {@code receive()}
     * 本来就硬校验 {@code ACTIVE}，状态一变，券从领券中心消失、领取直接被拒。
     *
     * <p><b>已领到手的券不受影响</b>——那是用户已经拿到的权益，
     * 平台单方面作废会引发比「多发了几张券」严重得多的纠纷。要收回得走另一条路。
     */
    public static final String PAUSED = "PAUSED";
    /** 已结束，不可恢复。 */
    public static final String ENDED = "ENDED";

    public static final String FULL_CUT = "FULL_CUT";
    public static final String DISCOUNT = "DISCOUNT";
    public static final String BY_PLATFORM = "PLATFORM";
    public static final String BY_MERCHANT = "MERCHANT";

    private String couponNo;
    private String title;
    private String type;

    private Long faceMinor;

    /** 折扣 ×100，88 = 8.8 折。 */
    private Integer discountRate;

    private Long thresholdMinor;

    /** 折扣券封顶，0 = 不封顶。 */
    private Long maxDiscountMinor;

    /** PLATFORM / MERCHANT —— 分账扣款对象。 */
    private String funder;

    /** 商家券限本店；平台券为空。 */
    private String entityNo;

    private Integer totalCount;
    private Integer receivedCount;
    private Integer perUserLimit;

    /**
     * 预算上限（分）。<b>0 = 不限</b>。
     *
     * <p>与 {@code totalCount}（发行张数）是两把不同的闸：张数管「发几张」，
     * 预算管「最多赔多少钱」。只有张数时，把面额从 5 元改成 50 元
     * 就能让同样的 1000 张变成十倍支出，而没有任何一处会拦。
     */
    private Long budgetMinor;

    private Long startAt;
    private Long endAt;
    private String status;

    /** 适用范围文案，如「仅限张记生鲜」。展示用，实际校验在服务端。 */
    private String scopeDesc;
}
