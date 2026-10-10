package ai.neargo.shop.common;

import java.util.Set;

/**
 * 履约方式的<b>唯一取值域</b>（落地清单 F-1）。
 *
 * <p>这四个值原本只存在于 {@code OrdSubOrder} 的常量里，而商品侧的
 * {@code prd_goods.fulfillments} 是一个<b>无取值域的自由 JSON 数组</b>，
 * 且建商品时被写死成 {@code ["STORE_PICKUP"]}、商家改不了。
 * 于是「这件商品支持怎么送」这件事在商品侧没有真正表达过，
 * 下单时也从不校验用户选的方式该商品支不支持。
 *
 * <p>抽到 base 而不是留在 trade：商品域与交易域都要用它，
 * 而商品域不能依赖交易域。<b>取值域属于两者之上的公共语言。</b>
 *
 * <p>注意这里<b>没有「平台仓发货」</b>——平台无仓、不碰货，
 * 只对接第三方物流（见方案 §7.4）。现有代码本来就是这么假设的，
 * 这里只是把它写明白。
 */
public final class Fulfillments {

    /** 到商家自有门店自取。 */
    public static final String STORE_PICKUP = "STORE_PICKUP";
    /** 到社区自提点自取（含邻居家自提点）。 */
    public static final String NEIGHBOR_PICKUP = "NEIGHBOR_PICKUP";
    /** 商家自送。<b>留痕最弱的一档</b>：自送、自签、自证，无任何独立第三方记录。 */
    public static final String MERCHANT_DELIVERY = "MERCHANT_DELIVERY";
    /** 第三方快递。留痕最强：独立运单，揽收与签收全程可查。 */
    public static final String EXPRESS = "EXPRESS";
    /**
     * 到店核销（服务品类）。<b>与 {@link #STORE_PICKUP} 是两件事</b>：
     * 自提是去代收点<b>取别人送来的货</b>，到店核销是去卖家门店
     * <b>消费自己买的服务</b>（见项目词典 Pickup vs Store verify）。
     *
     * <p>没有「发货」这一步：付款即出核销码，买家随时可去用掉。
     * 因此支付成功后直接落 {@code FULFILLING}，不经 {@code WAIT_FULFILL} ——
     * 把它丢进「待发货」，界面会说「待发货」而根本没有东西要发。
     */
    public static final String STORE_VERIFY = "STORE_VERIFY";
    /**
     * 上门预约（服务品类）。买家选定时段，服务方按时到约定地点。
     *
     * <p>与 {@link #STORE_VERIFY} 的差别只有两点，都不影响状态：
     * <ul>
     *   <li><b>必须有预约时间</b>（{@code appointment_at}）—— 没有时间的「待服务」等于没说</li>
     *   <li><b>上门要有地址</b> —— 与快递/自送同理，见 {@code requireReceiverWhenShipped}</li>
     * </ul>
     */
    public static final String APPOINTMENT = "APPOINTMENT";

    public static final Set<String> ALL = Set.of(
            STORE_PICKUP, NEIGHBOR_PICKUP, MERCHANT_DELIVERY, EXPRESS, STORE_VERIFY, APPOINTMENT);

    /**
     * 服务类履约：<b>没有前置的交付动作</b>，付款即可用 / 即已约定。
     *
     * <p>这一组决定支付成功后落哪个状态（见 {@code OrderServiceImpl} 的支付成功分支）：
     * 落 {@code FULFILLING} 而不是 {@code WAIT_FULFILL} —— 商家没有备货发货这一步。
     */
    public static final Set<String> SERVICE_LIKE = Set.of(STORE_VERIFY, APPOINTMENT);

    /** 要买家选定时段的履约方式。缺时间就下单，商家不知道该几点去。 */
    public static final Set<String> NEEDS_APPOINTMENT = Set.of(APPOINTMENT);

    /**
     * 实物类履约 —— <b>新建商品的默认集合</b>。
     *
     * <p>抽成常量是为了让「默认放宽」这个意图挡得住下一次扩充：
     * 默认原本是硬编码的四个值，加 {@code STORE_VERIFY} 时没受影响纯属侥幸 ——
     * 一件大米不该一建出来就声称支持到店核销。服务类履约要由商家<b>显式选择</b>，
     * 不进默认。接 {@code APPOINTMENT} 时同理。
     */
    public static final Set<String> PHYSICAL =
            Set.of(STORE_PICKUP, NEIGHBOR_PICKUP, MERCHANT_DELIVERY, EXPRESS);

    private Fulfillments() {
    }

    public static boolean isValid(String value) {
        return value != null && ALL.contains(value);
    }

    private static final Set<String> PICKUP = Set.of(STORE_PICKUP, NEIGHBOR_PICKUP);

    /** 是否为「到点自取」。自提与配送在校验、履约、结算上的分支都不同。 */
    public static boolean isPickup(String value) {
        return PICKUP.contains(value);
    }
}
