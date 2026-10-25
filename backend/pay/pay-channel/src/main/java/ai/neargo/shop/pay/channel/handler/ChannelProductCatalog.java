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

    /**
     * 收款前的运营开关。<b>只有产品表里显式停用的组合才返回 false</b>：没建模的渠道（I2 之前就在跑的）照旧放行 ——
     * 引入产品表不能把现有收款拦掉。键的地区为空（调用方还不知道地区）时，只要该渠道 × 接入模式 × 场景有任一开着的地区即可。
     */
    boolean payEnabled(ChannelKey key);
}
