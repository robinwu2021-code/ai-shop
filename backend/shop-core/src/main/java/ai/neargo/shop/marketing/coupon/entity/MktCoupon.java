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

    /**
     * 这张券对 {@code base} 元的商品能减多少（分）。
     *
     * <p><b>算折扣的地方只有这一处</b>。此前有两份：下单算价（CouponPortImpl）
     * 认得折扣券，而最优券试算（CouponService.best）只看 {@code faceMinor} ——
     * 折扣券的面额是 0，于是<b>「最优券」永远不推荐折扣券</b>：
     * 用户手动选能用，自动选选不出来，而两边的代码各自都说得通。
     *
     * <p>{@code discountRate} 是<b>万分比</b>：8500 = 八五折，与 ops-web 的展示
     * （{@code value / 1000}）和 CampaignType 的 DISCOUNT 同一口径。
     *
     * <p>实现此前用的是<b>百分数</b>（{@code (100 - rate) / 100}），
     * 而三处文档与前端都写着万分比 —— 一张按文档填了 8500 的券，
     * 算出来 {@code (100 - 8500)} 是负数：<b>优惠为负，等于加价</b>。
     * 库里当时一张折扣券都没有，所以没人撞上；趁没有数据的时候统一掉。
     *
     * @param base 参与计算的商品额（分）。商家券只算本店那部分
     */
    public long discountFor(long base) {
        if (DISCOUNT.equals(type)) {
            long off = base * (10_000 - (discountRate == null ? 0 : discountRate)) / 10_000;
            long cap = maxDiscountMinor == null ? 0 : maxDiscountMinor;
            return cap > 0 ? Math.min(off, cap) : off;
        }
        // 满减不能减成负数：券面额大于商品额时按商品额封顶
        return Math.min(faceMinor == null ? 0 : faceMinor, base);
    }

    /**
     * 归档时间。<b>软删除标记</b> —— 有值即从运营端默认列表消失，业务数据全保留。
     *
     * <p>与 {@code status} <b>正交</b>：暂停的还在列表里等着被恢复，
     * 归档的从列表消失。挤进同一列的话，「暂停后归档」会丢掉其中一个状态。
     */
    private java.time.LocalDateTime archivedAt;
}
