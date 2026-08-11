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

    public static final Set<String> ALL =
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
