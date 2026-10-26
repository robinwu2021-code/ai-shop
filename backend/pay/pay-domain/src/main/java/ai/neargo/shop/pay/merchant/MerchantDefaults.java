package ai.neargo.shop.pay.merchant;

/**
 * 收款商户的部署级默认（TDD-支付服务-I4进件与收款商户 S3 / S7）：回填与进件同一套，不各写一份。
 */
public final class MerchantDefaults {

    private MerchantDefaults() {
    }

    /** 微信收付通 / 支付宝走服务商；微信直连是商户自己的号；测试与桩渠道挂平台 */
    public static String accessModeOf(String payChannel, String wechatMode) {
        return switch (payChannel) {
            case "WECHAT" -> "ecommerce".equals(wechatMode) ? "SERVICE_PROVIDER" : "MERCHANT_OWN";
            case "ALIPAY" -> "SERVICE_PROVIDER";
            default -> "PLATFORM_SUB";
        };
    }

    /** 平台级（父）商户的渠道商户号：微信取配置的服务商号，没配或别的渠道用占位 */
    public static String platformChannelMerchantId(String payChannel, String wechatMchid) {
        return "WECHAT".equals(payChannel) && wechatMchid != null && !wechatMchid.isBlank()
                ? wechatMchid : "PLATFORM-" + payChannel;
    }

    /**
     * 市场 → 本币。{@code pay_channel_product_market} 按产品配，没有「市场本币」的一处真源（TDD 偏差 5）；
     * 没列的市场返回空，由调用方报冲突或拒绝。
     */
    public static String currencyOf(String market) {
        return market == null ? null : switch (market) {
            case "CN" -> "CNY";
            case "AE" -> "AED";
            case "SA" -> "SAR";
            default -> null;
        };
    }
}
