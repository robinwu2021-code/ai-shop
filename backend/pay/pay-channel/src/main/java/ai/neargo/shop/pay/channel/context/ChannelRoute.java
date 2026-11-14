package ai.neargo.shop.pay.channel.context;

import ai.neargo.shop.pay.channel.api.AccessMode;
import java.util.Objects;

/**
 * 造上下文要的输入：这笔交易落在哪个收款商户、哪种接入模式、哪个地区与币种（TDD-支付服务-W2渠道层补完 S4）。
 *
 * <p>收款商户路由（{@code pay_merchant}，I4）就绪的调用方带全；存量调用方（下单、退款、查单今天只知道渠道名与子商户号）
 * 用 {@link #of}，接入模式由工厂按本部署装的那一种补上。
 *
 * @param accessMode 为空 = 取本部署该渠道装的那一种（装了两种时工厂拒绝）
 * @param keyVersion 交易冻结的密钥版本（{@code sec_key_version}）；未知为 null
 */
public record ChannelRoute(String payChannel, AccessMode accessMode, String market, String currency,
                           Long payMerchantId, String payMerchantNo, String subMerchantId, Integer keyVersion) {

    public ChannelRoute {
        Objects.requireNonNull(payChannel, "payChannel");
    }

    public static ChannelRoute of(String payChannel, String currency, String subMerchantId) {
        return new ChannelRoute(payChannel, null, null, currency, null, null, subMerchantId, null);
    }

    public ChannelRoute withAccessMode(AccessMode mode) {
        return new ChannelRoute(payChannel, mode, market, currency, payMerchantId, payMerchantNo, subMerchantId,
                keyVersion);
    }

    public ChannelRoute withMarket(String m) {
        return new ChannelRoute(payChannel, accessMode, m, currency, payMerchantId, payMerchantNo, subMerchantId,
                keyVersion);
    }
}
