package ai.neargo.shop.pay.channel.handler;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 运营开着的支付产品（{@code pay_channel_product} × {@code pay_channel_product_market}），换算成收款要用的键。
 * 注册表启动时比对：表里开了、代码没实现 → 启动失败（渠道接入统一封装设计 §7-2）。
 */
public interface ChannelProductCatalog {

    /** 每个 ENABLED 产品在每个 ENABLED 地区上的 PAY 键 */
    Set<ChannelKey> enabledPayKeys();

    /**
     * 收款前的运营开关。<b>只有产品表里显式停用的组合才返回 false</b>：没建模的渠道（I2 之前就在跑的）照旧放行 ——
     * 引入产品表不能把现有收款拦掉。键的地区为空（调用方还不知道地区）时，只要该渠道 × 接入模式 × 场景有任一开着的地区即可。
     */
    boolean payEnabled(ChannelKey key);

    /**
     * 出通道金额精度（{@code pay_channel_product_market.amount_scale}）。地区为空时按币种找；找不到或同币种有两种精度为空，
     * 由调用方按币种默认小数位兜底（W2 S4 {@code ChannelContextFactory}）。
     */
    default OptionalInt amountScale(String payChannel, AccessMode accessMode, String market, String currency) {
        return OptionalInt.empty();
    }

    /**
     * 同一原单两次退款的最小间隔（{@code pay_channel_product.refund_interval_seconds}，取该渠道 × 接入模式各产品的最大值）；
     * 0 = 不限（W2 S7 退款间隔环）。
     */
    default int refundIntervalSeconds(String payChannel, AccessMode accessMode) {
        return 0;
    }
}
