package ai.neargo.shop.common;

import java.util.Set;

/**
 * 下单端的<b>唯一取值域</b>。
 *
 * <p><b>取值直接沿用 {@code ord_order.pay_scene} 的列注释</b>，不另起一套词 ——
 * 那一列从 V1 baseline 就存在（注释原文：'下单端 MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5'），
 * {@code stl_bill} 那份还多一句「<b>退款也要记</b> —— 退回原路，端不同接口不同」。
 * <b>缺的从来不是列，是写入</b>：全代码库搜不到一次 {@code setPayScene}。
 *
 * <p>用途有两个，且判定时读的端<b>不是同一个</b>：
 * <ul>
 *   <li><b>积分核销</b>（能不能抵扣）—— 读<b>当前请求</b>的端</li>
 *   <li><b>积分发放</b>（完成时发不发）—— 读<b>订单快照</b>上的这一列。
 *       因为发放时机是订单完成时，那一刻用户可能换了端，
 *       <b>更常见的是根本没有用户在场</b>（超时自动确认收货是系统动作）。
 *       读当前端会让同一笔订单发不发积分取决于谁在哪个端点的确认 ——
 *       不可复现、无法解释、也无法对账</li>
 * </ul>
 *
 * <p>⚠️ <b>这个值来自客户端请求头，天然可伪造。</b>
 * 所以它只能用于<b>平台侧策略</b>判定（哪个端允许用积分），
 * <b>绝不能用于权限或资金判定</b>。
 */
public final class PayScenes {

    /** 微信小程序。<b>最可能被关掉积分的那个端</b>：平台对虚拟支付有规则限制，尤其 iOS。 */
    public static final String MP_WECHAT = "MP_WECHAT";
    public static final String MP_ALIPAY = "MP_ALIPAY";
    public static final String IOS = "IOS";
    public static final String ANDROID = "ANDROID";
    public static final String H5 = "H5";

    public static final Set<String> ALL = Set.of(MP_WECHAT, MP_ALIPAY, IOS, ANDROID, H5);

    private PayScenes() {
    }

    public static boolean isValid(String value) {
        return value != null && ALL.contains(value);
    }
}
