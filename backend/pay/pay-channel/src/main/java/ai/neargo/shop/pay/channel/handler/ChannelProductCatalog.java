package ai.neargo.shop.pay.channel.handler;

import ai.neargo.shop.pay.channel.api.ChannelKey;
import java.util.Set;

/**
 * 运营开着的支付产品（{@code pay_channel_product} × {@code pay_channel_product_market}），换算成收款要用的键。
 * 注册表启动时比对：表里开了、代码没实现 → 启动失败（渠道接入统一封装设计 §7-2）。
 */
public interface ChannelProductCatalog {

    /** 每个 ENABLED 产品在每个 ENABLED 地区上的 PAY 键 */
    Set<ChannelKey> enabledPayKeys();

    /** 收款前的运营开关：这个键对应的产品此刻是否开着 */
    boolean payEnabled(ChannelKey key);
}
