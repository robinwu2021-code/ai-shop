package ai.neargo.shop.spi.member;

/**
 * trade → member：一单支付成功了。
 *
 * <p><b>会员的指标是订单的派生物</b>，所以入口只有这一个：交易域在支付成功那一刻告诉会员域，
 * 会员域自己决定要不要建关系、怎么累加。交易域不认识会员表，也不该认识。
 *
 * <p><b>实现必须幂等</b>（按 {@code subOrderNo} 去重）：支付回调会重发。
 */
public interface MemberEventPort {

    void onOrderPaid(OrderPaid event);

    /**
     * @param personNo    买家的人档号。<b>可能为空</b> —— 微信登录没授权手机号的人没有人档，
     *                    那时不入会。这是准入规则，不是校验失败
     * @param amountMinor 这一单在这家商家的实付额（分）
     */
    record OrderPaid(String subOrderNo, String userNo, String personNo,
                     String entityNo, String storeNo, long amountMinor, long paidAt) {
    }
}
