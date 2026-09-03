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
     * <b>成交口径的唯一定义处。</b>「这笔单算不算一次生意」——
     * 商家看板、跨店对比、顾客画像、平台看板、商家排行，全部引用这里。
     *
     * <p><b>为什么必须只有一份</b>：此前是两份，且没有人声明过它们是两份 ——
     * 商家侧写的是 {@code status != WAIT_PAY}（于是 <b>CANCELLED 也算成交</b>），
     * 平台侧写的是一个显式集合（不含 CANCELLED）。
     * 后果是同一个月、同一家店，商家后台的 GMV 比平台排行上的大，
     * 而两边各自都说得通、都不报错 —— 差额恰好是那些被取消的单。
     * 商家拿这个数去对账时，第一反应是平台少算了他的钱。
     *
     * <p><b>取消不是成交</b>：未付款自动关闭的单从来没有钱进来；
     * 付款后被平台强制取消的单，钱已经原路退回。两者都不该出现在任何 GMV 里。
     */
    public static final java.util.Set<String> PAID =
            java.util.Set.of(WAIT_FULFILL, FULFILLING, COMPLETED);

    /**
     * 成交口径 <b>+ 已退款</b>：GMV 是<b>毛</b>成交额，退款单要留在里面。
     *
     * <p>两条理由，缺一条这个集合就不成立：
     * <ul>
     *   <li><b>那笔钱确实成交过</b>。退款是之后发生的另一件事，它的出口在结算与售后，
     *       不是把历史成交抹掉</li>
     *   <li>不含它的话，<b>单子全退光的商家会从排行里整个消失</b> ——
     *       而那正是最该被看见的一家（实测：一笔 30 元的单低于即时退款阈值被自动退掉，
     *       那个商家在排行榜上凭空不见了）</li>
     * </ul>
     *
     * <p>「净成交」是另一个指标，属于结算域；不要在这里用减法凑出来 ——
     * 凑出来的数没有出处，对不上账时谁也说不清它是怎么来的。
     */
    public static final java.util.Set<String> TRANSACTED =
            java.util.Set.of(WAIT_FULFILL, FULFILLING, COMPLETED, REFUNDED);

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
    public static final String STORE_PICKUP = ai.neargo.shop.common.Fulfillments.STORE_PICKUP;
    public static final String NEIGHBOR_PICKUP = ai.neargo.shop.common.Fulfillments.NEIGHBOR_PICKUP;
    public static final String MERCHANT_DELIVERY = ai.neargo.shop.common.Fulfillments.MERCHANT_DELIVERY;
    public static final String EXPRESS = ai.neargo.shop.common.Fulfillments.EXPRESS;
    /** 到店核销（服务品类）。付款即出码，支付成功直接落 {@link #FULFILLING} */
    public static final String STORE_VERIFY = ai.neargo.shop.common.Fulfillments.STORE_VERIFY;

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

    /**
     * 下单时买家所属社区，<b>冗余自主单</b>（V137）。
     *
     * <p>存在的理由只有一个：**数据域的锚点必须是本表上的一列** ——
     * 拦截器在 SQL 层追加 where，join 出来的列它够不着。
     * 社区本来只在 {@code ord_order} 上，而运营会话可能带 COMMUNITY 维度；
     * 不冗余这一列的话，`ord_sub_order` 一旦接上数据域，
     * 配了社区域的运营打开订单页就是**整页空白且不报错**（fail-closed，见 V137 注释）。
     */
    private String communityNo;

    /** 自提点名称快照：页面要显示名字，不能只给号（C6）。 */
    private String pickupName;

    /**
     * 下单那一刻自提点的**承接方**，佣金归属依据（V317）。
     *
     * <p>不存的话，「这一单算谁的」要在结算时从 {@code pickup_no} 现推 ——
     * 而自提点换了承接门店之后，**历史订单的归属会跟着变**：
     * 上个月的单突然算到新承接方头上，而钱早就结给旧的了。
     *
     * <p>与同表的 {@code pickupName}、{@code receiver*} 是同一条理由
     * （「改名/改地址不该影响历史订单」），只是那些护的是显示，这一列护的是钱。
     */
    private String pickupOwnerRef;

    /** 下单时的承接门店号（STORE 类型自提点）。与 {@link #pickupOwnerRef} 成对 */
    private String pickupOwnerStoreNo;

    private String addressId;

    /**
     * 收件人快照（V69）。<b>不靠 {@link #addressId} 现查</b> ——
     * {@code usr_address} 可改可删：买家下完单改成新家，商家看到的就跟着变了，
     * 而货已经按旧地址在路上；删掉的话查出来是空，页面上什么提示都没有。
     *
     * <p>与上面 {@link #pickupName} 是同一个道理。
     */
    private String receiverName;

    /**
     * 收件人手机号，<b>库里存完整号</b>。
     *
     * <p>下发口径不在这里定，在 {@code MerchantOrderServiceImpl}：
     * 自送单给完整（送不到就得打电话），其余履约方式给后四位（B12）。
     * 存完整是因为这是这张单自己的数据 —— 不存的话事后补不回来。
     */
    private String receiverPhone;

    /** 收货地址快照（省市区 + 详细）。自提单为空 */
    private String receiverAddress;

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
     * 占用的预约时段；空 = 这家店没开时段，走 {@link #appointmentAt} 的旧路。
     *
     * <p>取消时靠它知道该把名额还给谁，也是出纠纷时「他到底约的哪一档」的凭据。
     */
    private String appointmentSlotNo;

    /**
     * 名额释放时刻。<b>幂等标记</b> —— 非空即已还过，不许再减 booked。
     *
     * <p>取消会被重放：超时关闭与用户手动取消可能同时到达。没有这个标记就会
     * 把名额还两次，于是 booked 减成负数、这个时段能卖出比 capacity 更多的单。
     * <b>释放要先条件 UPDATE 打这个标记，打上了才去减</b> ——
     * 反过来（先减再打）在并发重放下仍然会多减一次。
     */
    private Long appointmentReleasedAt;

    /**
     * 参团下单时的团号。
     * 此前契约里有、库里没有，接上去是**静默丢数据**：参团单变普通单，不报错。
     * 邻里自提的核销作用域也靠它裁剪（E16）。
     */
    private String groupNo;

    /** EXPRESS 履约：快递单号，发货后才有。 */
    private String expressNo;

    /**
     * 1 = <b>核销不等于完成</b>，必须买家确认收货（准入矩阵降级单，F-4）。
     *
     * <p>供货方就是自提点运营者时，「独立第三方核销」这道其实不存在 ——
     * 自己发货、自己核销、自己证明送到了。此时只剩买家能证明货到了手上。
     *
     * <p>在下单那一刻算好落库，不在核销时现算：现算的话商家事后把自提点换个人，
     * 历史单的判定就跟着变了。与 {@code business_mode} 快照同一个道理。
     */
    private Integer requireBuyerConfirm;

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
