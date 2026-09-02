package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 这一单能用哪些支付方式（C-1）。
 *
 * <h2>为什么不复用结算页的 {@code CheckoutCapabilityVO}</h2>
 * 那个是<b>下单前</b>按购物车算的，输入是「要买什么」；
 * 这个是<b>下单后</b>按订单算的，输入是「这一单里有哪些商家」。
 * 两者的交集规则一样，输入不一样 —— 合成一个的话，
 * 收银台要先把订单反推成购物车，那是一段没人愿意维护的代码。
 *
 * @param currency  这一单的记账币种。端上据它决定金额怎么显示
 * @param configured <b>是不是有商家配过支付方式。</b>
 *                  它与 {@code methods} 为空是<b>两件事</b>：
 *                  <ul>
 *                    <li>{@code configured = false} —— 进件还没走完，
 *                        端上<b>照常允许支付</b>（钱先欠着，与下单时一致）；</li>
 *                    <li>{@code configured = true} 而列表为空 ——
 *                        真的一种都不支持，端上要<b>拦住</b>。</li>
 *                  </ul>
 *                  合成一个空数组的话，一个完全正常的订单会被端上拦死。
 * @param methods   可用与不可用的都在里面，不可用的带原因
 */
public record OrderPayMethodVO(String currency, boolean configured, List<Method> methods) {

    /**
     * @param available         能不能用
     * @param unavailableReason 不能用的原因。<b>由服务端出文案</b> ——
     *                          端上自己拼的话，三端会拼出三个版本
     */
    public record Method(String methodCode, String payChannel, String name,
                         boolean available, String unavailableReason) {
    }
}
