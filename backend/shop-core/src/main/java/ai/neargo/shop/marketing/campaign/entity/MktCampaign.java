package ai.neargo.shop.marketing.campaign.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家营销活动。
 *
 * <p><b>为什么四类活动统一成一张表</b>：店铺券、满减、限时特价、买赠在数据上只差
 * 「触发条件 + 优惠方式」。各建一套的结果是四份几乎一样的增删改查，
 * 以及**四份互不知情的叠加规则** —— 而叠加恰恰是最容易算错的地方。
 *
 * <p>与平台侧的 {@code mkt_coupon} 分开：那张表的券由平台出资（{@code funder}），
 * 这里的活动一律商家自己出，分账时扣商家的钱。混表会让分账判断出资方时无从下手。
 */
@Getter
@Setter
@TableName("mkt_campaign")
public class MktCampaign extends BaseEntity {

    /** 店铺券：用户领取后在结算页抵扣。 */
    public static final String COUPON = "COUPON";
    /** 满减：满 X 减 Y，无需领取。 */
    public static final String FULL_CUT = "FULL_CUT";
    /** 限时特价：指定商品在时段内改价。 */
    public static final String FLASH = "FLASH";
    /** 买赠：买 N 送 M。 */
    public static final String BUY_GIFT = "BUY_GIFT";

    public static final String DRAFT = "DRAFT";
    public static final String RUNNING = "RUNNING";
    public static final String PAUSED = "PAUSED";
    public static final String ENDED = "ENDED";

    private String campaignNo;

    /** 活动是店铺级的，不跨店。 */
    private String entityNo;

    /**
     * 只对这家门店生效；<b>null = 全主体生效</b>（存量活动都是它）。
     *
     * <p><b>只有 FULL_CUT 允许有值。</b> FLASH 与 BUY_GIFT 改的是<b>商品页的展示</b>
     * （活动价、赠品标），而顾客浏览商品时还没选自提点 —— 也就无从知道这单会从
     * 哪家店出。允许它们限定门店，就会出现「页面显示 ¥9.90、下单变 ¥12.80」，
     * 而这正是回归清单里「金额一致」那条要防的。
     *
     * <p>COUPON 派生出的券有自己的核销链路，门店限定要在券侧做，暂不支持。
     */
    private String storeNo;

    /** 决定下面哪几个可空字段有意义。**创建后不可改** —— 改类型等于换一套优惠语义，应当新建。 */
    private String type;

    private String name;
    private String status;
    private Long startAt;
    private Long endAt;

    /** COUPON / FULL_CUT：门槛（分）。 */
    private Long thresholdMinor;

    /** COUPON / FULL_CUT：优惠额（分）。 */
    private Long discountMinor;

    /** FLASH：活动价（分）。 */
    private Long flashPriceMinor;

    /** BUY_GIFT：购买件数门槛。 */
    private Integer buyN;

    /** BUY_GIFT：赠送件数。 */
    private Integer giftM;

    /** 参与商品，JSON 数组；空 = 全店。 */
    private String goodsNos;

    /**
     * COUPON 发放总量，null = 不限量。
     * **预算上限，且必须在服务端校验** —— 客服也持有发券权限，只靠页面拦不住。
     */
    private Integer totalCount;

    private Integer takenCount;

    /** 已核销/已使用次数，衡量效果。 */
    private Integer usedCount;

    /**
     * 归档时间。<b>软删除标记</b> —— 有值即从运营端默认列表消失，业务数据全保留。
     *
     * <p>与 {@code status} <b>正交</b>：暂停的还在列表里等着被恢复，
     * 归档的从列表消失。挤进同一列的话，「暂停后归档」会丢掉其中一个状态。
     */
    private java.time.LocalDateTime archivedAt;
}
