package ai.neargo.shop.spi.pay;

import ai.neargo.shop.common.BizKey;

/**
 * 支付单号的识别（TDD-支付服务-I5接入层 S4）。第三方与收银台的交易尝试以支付单号作 {@code stl_payment.order_no}，
 * 于是回调与对账认领到的「订单号」可能是支付单号 —— 它不是交易单，交易侧补单要认得出来、跳过。
 * 收款成功改由支付事件通知业务方之后（S6），这一判断随直调一起退场。
 */
public final class PayOrderNumbers {

    private PayOrderNumbers() {
    }

    /** {@code PYO} 后紧跟数字：与交易单号、充值单号等其它前缀不会混 */
    public static boolean isPayOrderNo(String no) {
        String p = BizKey.PAY_ORDER;
        return no != null && no.length() > p.length() && no.startsWith(p) && Character.isDigit(no.charAt(p.length()));
    }
}
