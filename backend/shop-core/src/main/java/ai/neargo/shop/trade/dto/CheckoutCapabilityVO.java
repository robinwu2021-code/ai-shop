package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 结算页的<b>能力提示</b>：这一车货能不能开票、能用哪些支付方式、额度还够不够。
 *
 * <p>为什么单独一个接口而不是塞进 {@code OrderVO}：这三件事回答的是
 * 「<b>付款前你要先知道什么</b>」，与订单金额不是同一类信息；
 * 而且它们在下单之后就不再变化，塞进订单详情只会让每次查订单都多三次查询。
 *
 * <p>三件事一起给，是因为它们的共同后果都是<b>付款那一刻才炸</b>：
 * 小微没有 H5/App 支付方式，混合购物车整单支付失败；小微不能开票，
 * 买完才发现开不了；额度用尽，通道直接拒收。
 * 每一条单独看都像偶发故障，放在一起看才是同一件事 ——
 * 平台放小微进来了，而结算页还没告诉买家这意味着什么。
 *
 * @param usablePayMethods 整单可用的支付方式 = <b>各商家支持集合的交集</b>。
 *                         交集而非并集：一笔支付覆盖整单，有一家不支持就用不了。
 *                         <b>空数组 = 这一车货没有任何方式能付</b>，端上要拦在结算页；
 *                         <b>null = 未配置</b>（进件还没走完），端上<b>不要拦</b> ——
 *                         两者混成空数组的话，一个完全正常的订单会被拦死
 * @param merchants        逐商家的能力，端上据此在对应的商家分组上打标
 * @param usablePayModes   整单可用的<b>支付方式</b>（{@code PayModes}：ONLINE / OFFLINE）。
 *                         <p>⚠️ <b>与 {@code usablePayMethods} 是两根轴，别混</b>：
 *                         那个是<b>通道</b>（WECHAT / ALIPAY / H5…），这个是
 *                         <b>线上付还是当面付</b>。一笔订单要同时确定两者。
 *                         <p>同样取<b>交集</b>，同样因为一笔支付覆盖整单。
 *                         <b>ONLINE 永远在里面</b>（四层判定的约定），
 *                         所以这里不会是空集，也就不需要 null 那一档 ——
 *                         与 usablePayMethods 的取舍不同，因为那边真的可能「没配过」。
 */
public record CheckoutCapabilityVO(List<String> usablePayMethods,
                                   boolean anyNotInvoiceCapable,
                                   List<MerchantCapability> merchants,
                                   List<String> usablePayModes) {

    /**
     * @param quotaExhausted    本期额度已用尽 —— 这家的货现在下不了单
     * @param quotaWouldExceed  加上本车这些货会超 —— 还没用尽，但这一单过不去
     * @param deliveryLatE6     自送圆心（门店坐标，gcj02 E6）。<b>可能为 null</b> —— 门店没在地图上标过点
     * @param deliveryLngE6     同上
     * @param deliveryRadiusM   自送半径（米）。<b>可能为 null，也可能 ≤ 0</b>，两者都表示「不限距离」
     *
     * <p><b>这三个是给结算页把「送不到的地址」置灰用的。</b>此前端上完全不知道这件事，
     * 用户挑一个地址、填完、点提交，才撞上 {@code OUT_OF_DELIVERY_RANGE} ——
     * 而那时他既不知道哪家送不到，也不知道该换哪个地址。
     *
     * <p>端上判的口径必须与 {@code requireWithinDeliveryRadius} 完全一致，
     * <b>包括三条放行</b>（地址没坐标 / 门店没坐标 / 半径 ≤ 0）。
     * 端上比后端严，会把本来下得成的单挡在门外。
     */
    public record MerchantCapability(String merchantNo, String merchantName,
                                     boolean invoiceCapable, List<String> payMethods,
                                     boolean quotaExhausted, boolean quotaWouldExceed,
                                     Integer deliveryLatE6, Integer deliveryLngE6,
                                     Integer deliveryRadiusM) {
    }
}
