package ai.neargo.shop.common;

import java.util.Set;

/**
 * 支付方式的<b>唯一取值域</b>：钱经不经过平台。
 *
 * <p><b>与 {@link Fulfillments} 正交</b> —— 那是「东西怎么交付」，这是「钱怎么付」。
 * 把两者揉成一个枚举会得到一个笛卡尔积，而其中大半组合没有业务含义
 * （「快递 + 到店付款」是什么？）。合法组合由下单时的校验声明，不由取值域表达。
 *
 * <p>放 {@code shop-base} 而不是留在某个域：商品域要存「这件货支持哪些」、
 * 交易域要校验「用户选的这个支不支持」、结算域要按它分流 ——
 * <b>取值域属于这三者之上的公共语言</b>，与 {@link Fulfillments} 同一条理由。
 *
 * <p>⚠️ <b>这一列不能重蹈 {@code prd_goods.fulfillments} 的覆辙。</b>
 * 那一列曾经是「无取值域的自由 JSON 数组、建商品时被写死成 {@code ["STORE_PICKUP"]}、
 * 商家改不了」，于是「这件商品支持怎么送」在商品侧从没真正表达过，
 * 下单时也从不校验。所以<b>取值域常量、建品可选、下单校验</b>这三件事要一起做完。
 */
public final class PayModes {

    /** 线上支付：钱经平台账户，走分账（ADR-002）。 */
    public static final String ONLINE = "ONLINE";

    /**
     * 线下支付：买家把钱<b>当面付给商家</b>，平台不碰这笔钱。
     *
     * <p>因此它<b>不进分账、不抽佣、不给平台补贴</b>（已拍板）。
     * 但**积分与商家券照常可用** —— 那两样的成本本来就在商家，
     * 商家当面少收即是抵扣，平台不需要任何资金动作。
     * 只有<b>平台券</b>不行：平台要把补贴的钱给商家，而线下没有资金流可补。
     */
    public static final String OFFLINE = "OFFLINE";

    public static final Set<String> ALL = Set.of(ONLINE, OFFLINE);

    /** 新建商品的默认集合。<b>只给线上</b> —— 线下收款要商家显式开，不该一建出来就支持。 */
    public static final Set<String> DEFAULT = Set.of(ONLINE);

    private PayModes() {
    }

    public static boolean isValid(String value) {
        return value != null && ALL.contains(value);
    }
}
