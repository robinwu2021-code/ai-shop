package ai.neargo.shop.pay.callback;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 注册表启动校验三（渠道接入统一封装设计 §7；TDD-支付服务-I2渠道统一封装 S7） */
class CallbackCoverageCheckTest {

    private static ChannelHandler handler(String channel, AccessMode mode) {
        return new ChannelHandler() {
            @Override
            public Set<ChannelKey.Pattern> supports() {
                return Set.of(new ChannelKey.Pattern(null, channel, mode, PayScene.ANY, ChannelOperation.PAY));
            }

            @Override
            public ChannelResult handle(Invocation inv, ChannelCommand command) {
                return new ChannelResult.Unknown("unused");
            }
        };
    }

    private static final ChannelProductCatalog NONE = new ChannelProductCatalog() {
        @Override
        public Set<ChannelKey> enabledPayKeys() {
            return Set.of();
        }

        @Override
        public boolean payEnabled(ChannelKey key) {
            return true;
        }
    };

    private static ChannelCallbackVerifier verifier(String channel) {
        return new ChannelCallbackVerifier() {
            @Override
            public String payChannel() {
                return channel;
            }

            @Override
            public Map<String, Object> verify(Map<String, String> headers, String rawBody) {
                return null;
            }

            @Override
            public String ackOk() {
                return "ok";
            }

            @Override
            public String ackFail() {
                return "fail";
            }
        };
    }

    private static void check(List<ChannelHandler> handlers, List<ChannelCallbackVerifier> verifiers) {
        var template = new CallbackTemplate(verifiers, null, null, null, null, null);
        new CallbackConfiguration().callbackCoverageCheck(new ChannelHandlerRegistry(handlers, NONE), template)
                .afterSingletonsInstantiated();
    }

    @Test
    @DisplayName("★★★ 装了微信收款处理器、没有微信回调验签 → 启动失败（渠道推来的结果会全被当成未知通道丢掉）")
    void externalChannelWithoutCallbackFails() {
        assertThatThrownBy(() -> check(List.of(handler("WECHAT", AccessMode.MERCHANT_OWN)), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WECHAT");
        assertThatCode(() -> check(List.of(handler("WECHAT", AccessMode.MERCHANT_OWN)), List.of(verifier("WECHAT"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 平台自有账户（测试、stub、免单）没有外部回调，不要求")
    void platformAccountsAreExempt() {
        assertThatCode(() -> check(List.of(handler("TEST", AccessMode.PLATFORM_SUB)), List.of()))
                .doesNotThrowAnyException();
    }
}
