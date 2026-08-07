package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 子订单：**商家视角**——一个 {@code merchantNo}、一次分账、一条履约链、一条售后链。
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

    private String subOrderNo;
    private String orderNo;
    private String userNo;
    private String merchantNo;

    /** 下单时快照：商家改名不影响历史订单的展示。 */
    private String merchantName;

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
}
