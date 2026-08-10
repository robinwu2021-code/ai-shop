package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 子订单：**商家视角**——一个 {@code entityNo}、一次分账、一条履约链、一条售后链。
 *
 * <p>{@code trafficSource} 在这一层而不是主单：一次下单可能一半商品来自店铺码进店、
 * 一半来自平台首页，费率分档必须按子单算（R16/B10）。
 *
 * <p>它在**下单那一刻**由 {@code AttributionPort} 固化写入，不是结算时回查 ——
 * 用户中途扫了别家店铺码，归因就变了，而这类争议事后没有举证材料（TDD-backend §7.4）。
 */
@Getter
@Setter
@TableName("ord_sub_order")
public class OrdSubOrder extends BaseEntity {

    public static final String WAIT_PAY = "WAIT_PAY";
    public static final String WAIT_FULFILL = "WAIT_FULFILL";
    public static final String FULFILLING = "FULFILLING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REFUNDED = "REFUNDED";

    /**
     * {@code fulfillment} 列的取值域。
     *
     * <p>此前这四个值只以字面量形式散在各处（{@code MERCHANT_DELIVERY} 全后端仅一处），
     * 建表语句上也没有注释 —— 也就是说**没有任何地方声明过这一列合法值是哪些**。
     * 代价：端上写了 {@code "DELIVERY"}，两边各自都「有那个词」，
     * 全局词汇表比对照样通过，而确认订单页把 i18n 键原样打给了用户。
     *
     * <p>集中在这里是为了给 {@code scripts/check-enums.mjs} 一个可靠的真源 ——
     * 它按**字段**比对取值域，而不是在全部大写字面量里碰运气。
     */
    public static final String STORE_PICKUP = "STORE_PICKUP";
    public static final String NEIGHBOR_PICKUP = "NEIGHBOR_PICKUP";
    public static final String MERCHANT_DELIVERY = "MERCHANT_DELIVERY";
    public static final String EXPRESS = "EXPRESS";

    private String subOrderNo;
    private String orderNo;
    private String userNo;
    private String entityNo;

    /**
     * 履约门店（M2 双写）。<b>结算仍按 {@link #entityNo}</b> ——
     * 两个键答两个问题：钱走主体（通道按营业执照发号），货与评价走门店。
     *
     * <p>可空：历史订单或主体没有门店行时留空，履约侧按「空 → 默认门店」兜底。
     */
    private String storeNo;

    /** 下单时快照：商家改名不影响历史订单的展示。 */
    private String entityName;

    /** STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS */
    private String fulfillment;

    private String pickupNo;

    /** 自提点名称快照：页面要显示名字，不能只给号（C6）。 */
    private String pickupName;

    private String addressId;

    /** MERCHANT_OWNED / PLATFORM —— 决定费率档位，下单时固化。 */
    private String trafficSource;

    private Long goodsAmount;
    private Long freightAmount;
    private Long discountAmount;

    /**
     * 优惠的**出资方**拆开存（C4 / Q9）：平台券平台出、商家足额收款；
     * 商家券商家自己出、分账时扣减。合成一列的话，M7 分账无法判断该扣谁的钱。
     */
    private Long discountPlatform;
    private Long discountMerchant;

    private Long payAmount;

    /** WAIT_PAY / WAIT_FULFILL / FULFILLING / COMPLETED / CANCELLED / REFUNDED */
    private String status;

    /** 核销码：自提码/核销码/兑换码三态共用，支付成功后生成，**全局唯一**（C4）。 */
    private String verifyCode;

    private String remark;

    /**
     * 称重差价（分）：**正=补款 负=退款**。
     * 生鲜按标称重量收钱，实际称重后才知道差多少 —— 不留这一列，差价既算不出来也无处记账。
     */
    private Long weighAdjustMinor;

    /** APPOINTMENT 履约：预约开始时间戳。 */
    private Long appointmentAt;

    /**
     * 参团下单时的团号。
     * 此前契约里有、库里没有，接上去是**静默丢数据**：参团单变普通单，不报错。
     * 邻里自提的核销作用域也靠它裁剪（E16）。
     */
    private String groupNo;

    /** EXPRESS 履约：快递单号，发货后才有。 */
    private String expressNo;

    /** 下单人昵称快照：团长视角（分拣单/核销台）要看得见是谁的单。 */
    private String buyerNickname;

    /** 是否已评价 —— 一单一评的判据。 */
    private Boolean reviewed;
    /**
     * 商家实收（分）= 实付 − 通道手续费 − 平台佣金 − 履约服务费 − 积分费用金。
     *
     * <p><b>支付时就算定</b>：四项扣减那一刻全部可知。不算的话，商家从下单到售后期结束
     * 看到的是一个比实收大的数字，等结算单出来才发现「怎么少了」—— 而这完全可以避免。
     *
     * <p>⚠️ 称重差价（{@code weighAdjustMinor}）实称后会改变实付，届时本列要跟着重算。
     */
    private Long merchantRecvMinor;

    /** 通道手续费（分）：微信/支付宝扣的，支付时即确定。 */
    private Long channelFeeMinor;

    /** 平台佣金（分）：费率按 trafficSource 分档，下单时快照。 */
    private Long commissionMinor;

    /** 履约服务费（分）：自提单给承接方，非自提单为 0。 */
    private Long serviceFeeMinor;


    /** 本单抵扣的积分数。**平台内部字段，不下发商家端** —— 商家按订单全额收款。 */
    private Integer pointsDeduct;

    /** 本单积分抵扣金额（分）。上限 = 券后金额 30%，运费不参与。平台内部字段。 */
    private Long pointsDeductMinor;

    /** 发放幂等标记：防重复核销重复发分。 */
    private Boolean pointsGranted;

    /** 本单发放积分的服务费（分）。发放时算定，结算时从货款扣走进积分池。 */
    private Long pointsFeeMinor;
}
