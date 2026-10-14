package ai.neargo.shop.spi.marketing;

/**
 * trade → marketing：读用户在某商家下的<b>当前</b>归因来源。
 *
 * <p>调用时机只有一个 —— <b>下单那一刻</b>，结果固化到子订单（TDD-backend §7.4）。
 * 结算时再回查是错的：用户中途扫了别家店铺码，归因会变，费率跟着变，
 * 而这类问题事后没有任何举证材料。
 */
public interface AttributionPort {

    /** 商家自带客流（店铺码/店铺分享进入）。一期建议零佣金（R16/B10）。 */
    String MERCHANT_OWNED = "MERCHANT_OWNED";

    /** 平台客流（首页/搜索/频道进入）。 */
    String PLATFORM = "PLATFORM";

    /**
     * @return {@link #MERCHANT_OWNED} 或 {@link #PLATFORM}，永不为 null
     */
    String resolveTrafficSource(String userNo, String merchantNo);

    /**
     * 当前归属到的商家（店铺码归因命中时才有值）。用于「我的常去店」把刚扫码的那家排最前。
     *
     * @return merchantNo；无归属、已过窗口期、或归因来源不是店铺码时返回 null
     */
    String attributedMerchant(String userNo);

    /**
     * 首单回填。<b>下单成功那一刻调一次</b>，marketing 侧据此把「这次归因/这次邀请
     * 到底有没有变成生意」记下来。
     *
     * <p><b>为什么必须有这一步</b>：归因日志的 {@code order_no} 与裂变台账的
     * {@code order_no} 两列的注释都写着「由 ORDER_CREATED 事件回填」，
     * 而在此之前**没有任何代码写过它们** —— 于是「归因值不值钱」「邀请转化了几个」
     * 这两个问题在数据里永远是空的，运营端「邀请有礼」那两列因此恒为 0。
     *
     * <p><b>幂等</b>：只回填「还没有首单」的那些行，重复调用不会把第二单当成首单。
     *
     * <p><b>失败不打断下单</b>：这是统计口径，不是交易的一部分。实现里吞掉异常，
     * 让一次营销统计失败去回滚一笔已经收了钱的订单，代价方向完全反了。
     */
    void onFirstOrder(String userNo, String orderNo);
}
