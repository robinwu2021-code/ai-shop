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
}
