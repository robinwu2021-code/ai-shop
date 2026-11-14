package ai.neargo.shop.pay.channel.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.capability.ChannelCapability;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.capability.OnlinePayCapability;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文工厂（TDD-支付服务-W2渠道层补完 S4 · AC5）：{@code ChannelAccountContext} 只在这里造。
 *
 * <p>此前三处各造各的（桥、补查、单测），精度一处按币种默认、一处写死 2，接入模式一处从网关类推、一处从任务行取 ——
 * 同一笔交易的查单与补查可以带着两种精度去问渠道。收口到一处后，「用谁的身份、按什么精度」只有一个答案。
 */
class ChannelContextFactoryTest {

    private static OnlinePayCapability online(String channel, AccessMode mode) {
        return new OnlinePayCapability() {
            @Override
            public Set<PayScene> onlineScenes() {
                return Set.of(PayScene.ONLINE_SDK);
            }

            @Override
            public ChannelResult payOnline(Invocation inv, PayCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String payChannel() {
                return channel;
            }

            @Override
            public AccessMode accessMode() {
                return mode;
            }
        };
    }

    /** TW / TWD 在 NEWEBPAY 上精度 0；其余没登记 */
    private static final ChannelProductCatalog CATALOG = new ChannelProductCatalog() {
        @Override
        public Set<ChannelKey> enabledPayKeys() {
            return Set.of();
        }

        @Override
        public boolean payEnabled(ChannelKey key) {
            return true;
        }

        @Override
        public OptionalInt amountScale(String payChannel, AccessMode accessMode, String market, String currency) {
            return "NEWEBPAY".equals(payChannel) && "TWD".equals(currency) ? OptionalInt.of(0) : OptionalInt.empty();
        }
    };

    private final ChannelContextFactory factory = new ChannelContextFactory(new ChannelCapabilityRegistry(List.<ChannelCapability>of(
            online("WECHAT", AccessMode.MERCHANT_OWN),
            online("NEWEBPAY", AccessMode.MERCHANT_OWN),
            online("SPLIT", AccessMode.MERCHANT_OWN), online("SPLIT", AccessMode.SERVICE_PROVIDER))), CATALOG);

    @Test
    @DisplayName("★★★ AC5 接入模式：路由带了就用路由的；没带取本部署装的那一种；装了两种或没装 → 说清楚再炸")
    void accessModeResolution() {
        assertThat(factory.from(ChannelRoute.of("WECHAT", "CNY", "SUB1")).accessMode()).isEqualTo(AccessMode.MERCHANT_OWN);
        assertThat(factory.from(ChannelRoute.of("SPLIT", "CNY", null).withAccessMode(AccessMode.SERVICE_PROVIDER))
                .accessMode()).isEqualTo(AccessMode.SERVICE_PROVIDER);

        assertThatThrownBy(() -> factory.from(ChannelRoute.of("SPLIT", "CNY", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("装了不止一种接入模式");
        assertThatThrownBy(() -> factory.from(ChannelRoute.of("NOPE", "CNY", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有装配");
    }

    @Test
    @DisplayName("★★★ AC5 精度：产品地区行登记了就用它（TWD 在蓝新是 0），否则按币种默认小数位")
    void amountScale() {
        assertThat(factory.from(new ChannelRoute("NEWEBPAY", null, "TW", "TWD", null, null, null, null)).amountScale())
                .isZero();
        assertThat(factory.from(ChannelRoute.of("WECHAT", "CNY", null)).amountScale()).isEqualTo(2);
        assertThat(factory.from(ChannelRoute.of("WECHAT", "JPY", null)).amountScale()).isZero();
        // 查单不带币种：按 2，与 I2 起的口径一致
        assertThat(factory.from(ChannelRoute.of("WECHAT", null, null)).amountScale()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ AC5 收款商户身份与冻结的密钥版本原样带进上下文（进调用记录、取凭据都按它）")
    void merchantIdentityPassesThrough() {
        ChannelAccountContext ctx = factory.from(new ChannelRoute("WECHAT", AccessMode.MERCHANT_OWN, "CN", "CNY", 42L,
                "PM42", "SUB42", 3));

        assertThat(ctx.market()).isEqualTo("CN");
        assertThat(ctx.payMerchantId()).isEqualTo(42L);
        assertThat(ctx.payMerchantNo()).isEqualTo("PM42");
        assertThat(ctx.subMerchantId()).isEqualTo("SUB42");
        assertThat(ctx.keyVersion()).isEqualTo(3);
        assertThat(ctx.currency()).isEqualTo("CNY");
    }
}
