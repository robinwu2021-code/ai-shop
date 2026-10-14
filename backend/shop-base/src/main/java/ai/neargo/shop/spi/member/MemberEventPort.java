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
     * user → member：这份人档刚绑上账号。
     *
     * <p>会员域据此把它名下所有线索会员转正 —— <b>一次绑定，几家商家的会员同时生效</b>。
     *
     * <p>为什么由 user 域主动喊而不是会员域去轮询：转正必须在他登录那一刻就完成，
     * 否则他进店看到的还是「未加入」，而商家那边明明早就录过他。
     *
     * @return 转正了几条。调用方只用于日志，不据此决定成败
     */
    int onPersonBound(String personNo);

    /**
     * @param personNo    买家的人档号。<b>可能为空</b> —— 微信登录没授权手机号的人没有人档，
     *                    那时不入会。这是准入规则，不是校验失败
     * @param amountMinor 这一单在这家商家的实付额（分）
     */
    record OrderPaid(String subOrderNo, String userNo, String personNo,
                     String entityNo, String storeNo, long amountMinor, long paidAt) {
    }
}
