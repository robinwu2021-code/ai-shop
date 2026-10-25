package ai.neargo.shop.pay.channel.api;

import java.util.Objects;

/**
 * 以谁的身份、在哪个地区调渠道（渠道接入统一封装设计 §3）。凭据由处理器按 {@code payMerchantId} / 子商户号取，
 * 不放在这里 —— 这个对象会进日志与调用记录。
 *
 * @param currency    该地区收款币种（{@code pay_channel_product_market.currency}）
 * @param amountScale 发给渠道的金额精度（TWD 在蓝新 / LINE Pay 为 0，CNY 为 2）
 */
public record ChannelAccountContext(String market, String payChannel, AccessMode accessMode,
                                    Long payMerchantId, String payMerchantNo, String subMerchantId,
                                    String currency, int amountScale) {

    public ChannelAccountContext {
        Objects.requireNonNull(payChannel, "payChannel");
        Objects.requireNonNull(accessMode, "accessMode");
        if (amountScale < 0 || amountScale > 3) {
            throw new IllegalArgumentException("amountScale 只能是 0–3：" + amountScale);
        }
    }
}
