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
}
