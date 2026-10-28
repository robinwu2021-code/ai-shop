package ai.neargo.shop.pay.channel.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 测试银行必须真的拒绝关闭后的付款，而不只是返回一个成功关单回执。 */
class TestChannelCloseTest {
    private final TestPayGateway bank = new TestPayGateway();
    private final InProcessChannel channel = new InProcessChannel(bank);

    private ChannelResult close(String no) {
        var catalog = new ChannelProductCatalog() {
            @Override public Set<ChannelKey> enabledPayKeys() { return Set.of(); }
            @Override public boolean payEnabled(ChannelKey key) { return true; }
        };
        var account = new ChannelContextFactory(new ChannelCapabilityRegistry(List.of(channel)), catalog)
                .from(ChannelRoute.of("TEST", "CNY", null));
        var command = new CloseCommand(new InvocationMeta(null, 1L, 2L, null, null,
                SourceInfo.system("CLOSE", no)), no);
        return channel.close(Invocation.start(new ChannelKey("CN", "TEST", AccessMode.PLATFORM_SUB,
                PayScene.ANY, command.operation()), account, command), command);
    }

    @Test
    void closedOrderCannotBePaidOrReopened() {
        bank.placeOrder("CLOSE-1", 100);
        assertThat(close("CLOSE-1")).isInstanceOf(ChannelResult.Succeeded.class);
        assertThat(bank.markPaid("CLOSE-1")).isFalse();
        assertThat(bank.prepay(new PayGateway.PrepayCommand("CLOSE-1", 100, "CNY", "s", "JSAPI", null, null))
                .success()).isFalse();
        assertThat(close("CLOSE-1")).isInstanceOf(ChannelResult.Succeeded.class);
    }

    @Test
    void paidOrderCannotBeClosed() {
        bank.placeOrder("PAID-1", 100);
        bank.markPaid("PAID-1");
        assertThat(close("PAID-1")).isNotInstanceOf(ChannelResult.Succeeded.class);
        assertThat(bank.query("PAID-1").paid()).isTrue();
    }
}
