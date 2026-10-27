package ai.neargo.shop.pay.channel.context;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import java.util.Currency;
import java.util.OptionalInt;
import java.util.Set;

/**
 * {@link ChannelAccountContext} 的<b>唯一</b>构造者（复核 §3.2 W2 · 代码结构与类清单 §5.4；TDD-支付服务-W2渠道层补完 S4）。
 *
 * <p>三件事在这里定、只在这里定：
 * <ol>
 *   <li><b>接入模式</b>：路由带了就用；没带取本部署该渠道装的那一种 —— 装了两种（直连与收付通并存）时拒绝，
 *       不替调用方挑：挑错等于把钱发到另一种商户号的接口上；</li>
 *   <li><b>金额精度</b>：产品地区行（{@code pay_channel_product_market.amount_scale}）登记了就用，否则按币种默认小数位，
 *       币种未知按 2（I2 起的口径）；</li>
 *   <li><b>收款商户身份与冻结的密钥版本</b>原样带进去（凭据经密钥模块按它开，W5）。</li>
 * </ol>
 * 凭据本身不进上下文 —— 上下文会进日志与调用记录。
 */
public final class ChannelContextFactory {

    private static final int DEFAULT_SCALE = 2;

    private final ChannelCapabilityRegistry capabilities;
    private final ChannelProductCatalog catalog;

    public ChannelContextFactory(ChannelCapabilityRegistry capabilities, ChannelProductCatalog catalog) {
        this.capabilities = capabilities;
        this.catalog = catalog;
    }

    /**
     * @throws IllegalStateException 渠道在本部署没装，或装了不止一种接入模式而路由没指明
     */
    public ChannelAccountContext from(ChannelRoute route) {
        AccessMode mode = route.accessMode() != null ? route.accessMode() : defaultMode(route.payChannel());
        return new ChannelAccountContext(route.market(), route.payChannel(), mode, route.payMerchantId(),
                route.payMerchantNo(), route.subMerchantId(), route.currency(), scale(route, mode), route.keyVersion());
    }

    private AccessMode defaultMode(String payChannel) {
        Set<AccessMode> modes = capabilities.accessModes(payChannel);
        if (modes.isEmpty()) {
            throw new IllegalStateException("支付渠道 " + payChannel + " 在本部署没有装配任何能力实现");
        }
        if (modes.size() > 1) {
            throw new IllegalStateException("支付渠道 " + payChannel + " 装了不止一种接入模式 " + modes
                    + "，调用方必须按收款商户指明接入模式（ChannelRoute.accessMode）");
        }
        return modes.iterator().next();
    }

    private int scale(ChannelRoute route, AccessMode mode) {
        OptionalInt fromCatalog = route.currency() == null && route.market() == null ? OptionalInt.empty()
                : catalog.amountScale(route.payChannel(), mode, route.market(), route.currency());
        if (fromCatalog.isPresent()) {
            return fromCatalog.getAsInt();
        }
        if (route.currency() == null) {
            return DEFAULT_SCALE;
        }
        try {
            return Math.max(0, Currency.getInstance(route.currency()).getDefaultFractionDigits());
        } catch (IllegalArgumentException unknownCurrency) {
            return DEFAULT_SCALE;
        }
    }
}
