package ai.neargo.shop.pay.channel.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.channel.FreePayGateway;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.ChannelInvoker;
import ai.neargo.shop.pay.channel.invoke.interceptor.ClassifyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RecordingInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RetryInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ValidationInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.spi.pay.RefundQueryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 进程内渠道（TEST / STUB / FREE）的能力实现（TDD-支付服务-W2渠道层补完 S5 · AC6）。
 *
 * <p>40 余个 CN 场景测试把 {@code TestPayGateway} 当「测试银行」用：间谍它的 {@code prepay} / {@code refund} /
 * {@code queryRefund}，按次数断言。{@code LegacyGatewayHandler} 删掉之后，这层形状必须原样保住 —— 这里钉的就是
 * 「桥 → 管线 → 能力实现 → 测试银行」与「桥 → 网关」对调用方逐字等价：参数、结果、调用次数。
 */
class InProcessChannelTest {

    private final List<PayChannelCallLog> rows = new ArrayList<>();
    private final TestPayGateway bank = spy(new TestPayGateway());
    private final LegacyChannelBridge bridge = bridge(new InProcessChannel(bank), new InProcessChannel(new FreePayGateway()));

    @Test
    @DisplayName("★★★ 下单：端上参数与测试银行给的逐字相同；经管线、留一行调用记录")
    void prepayIsVerbatim() {
        var res = bridge.prepay("TEST", new PayGateway.PrepayCommand("OUT-1", 1234, "CNY", "s", "JSAPI", "SUB", "open"));

        assertThat(res.success()).isTrue();
        assertThat(res.tradeNo()).isEqualTo("TESTTX-OUT-1");
        assertThat(res.params()).containsEntry("prepayId", "test_OUT-1").containsEntry("outTradeNo", "OUT-1")
                .containsEntry("amount", "1234").containsEntry("testChannel", "true");
        verify(bank).prepay(new PayGateway.PrepayCommand("OUT-1", 1234, "CNY", "s", "JSAPI", "SUB", "open"));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getOperation()).isEqualTo("PAY");
            assertThat(r.getAccessMode()).isEqualTo(AccessMode.PLATFORM_SUB.name());
        });
    }

    @Test
    @DisplayName("★★ 零金额不进管线（校验会拒 0 元），直达测试银行：它明确拒绝，原样带回")
    void zeroAmountBypassesPipeline() {
        var res = bridge.prepay("TEST", new PayGateway.PrepayCommand("OUT-0", 0, "CNY", "s", "JSAPI", null, null));

        assertThat(res.success()).isFalse();
        assertThat(res.message()).contains("金额必须大于 0");
        assertThat(rows).isEmpty();
        var free = bridge.prepay("FREE", new PayGateway.PrepayCommand("OUT-F", 0, "CNY", "s", "JSAPI", null, null));
        assertThat(free.success()).isTrue();
        assertThat(free.params()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 查单三种答案分得开：已付 / 未付 / 渠道没有这笔")
    void queryThreeWays() {
        assertThat(bridge.query("TEST", "NOPE").found()).isFalse();
        assertThat(bridge.query("TEST", "NOPE").ok()).isTrue();
        bank.placeOrder("O-1", 500);
        assertThat(bridge.query("TEST", "O-1").paid()).isFalse();
        bank.markPaid("O-1");
        var paid = bridge.query("TEST", "O-1");
        assertThat(paid.paid()).isTrue();
        assertThat(paid.amountMinor()).isEqualTo(500);
        assertThat(paid.tradeNo()).isEqualTo("TESTTX-O-1");
    }

    @Test
    @DisplayName("★★★ 退款：受理带渠道单号；银行不明确的失败 = 不明（只查不重发），原因原样带回")
    void refund() {
        bank.placeOrder("O-2", 500);
        bank.markPaid("O-2");
        var ok = bridge.refund("TEST", new PayGateway.TxContext(null, "TESTTX-O-2", "O-2", 500), 200, "R-2", "r");
        assertThat(ok.success()).isTrue();
        assertThat(ok.providerNo()).isEqualTo("TESTRF-R-2");
        verify(bank, times(1)).refund(new PayGateway.TxContext(null, "TESTTX-O-2", "O-2", 500, "CNY"), 200, "R-2", "r");

        var missing = bridge.refund("TEST", new PayGateway.TxContext(null, "X", "NO-ORDER", 500), 100, "R-3", "r");
        assertThat(missing.success()).isFalse();
        assertThat(missing.definitiveRejection()).isFalse();
        assertThat(missing.retryable()).isFalse();
        assertThat(missing.message()).contains("原单不存在");
        verify(bank, times(1)).refund(new PayGateway.TxContext(null, "X", "NO-ORDER", 500, "CNY"), 100, "R-3", "r");
    }

    @Test
    @DisplayName("★★★ 查退款：没有这笔 ≠ 查不通；五态回执与旧默认实现同口径；测试银行只被问一次")
    void refundQueries() {
        assertThat(bridge.queryRefund("TEST", "R-X").ok()).isTrue();
        assertThat(bridge.queryRefund("TEST", "R-X").found()).isFalse();
        assertThat(bridge.queryRefundOutcome("TEST", "R-X").status()).isEqualTo(RefundQueryResult.Status.UNKNOWN);

        bank.placeOrder("O-4", 500);
        bank.markPaid("O-4");
        bridge.refund("TEST", new PayGateway.TxContext(null, "TESTTX-O-4", "O-4", 500), 300, "R-4", "r");
        RefundQueryResult done = bridge.queryRefundOutcome("TEST", "R-4");
        assertThat(done.status()).isEqualTo(RefundQueryResult.Status.SUCCEEDED);
        assertThat(done.amountMinor()).isEqualTo(300);
        assertThat(done.providerNo()).isEqualTo("TESTRF-R-4");
        assertThat(done.currency()).isEqualTo("CNY");
        assertThat(done.outRefundNo()).isEqualTo("R-4");
        assertThat(done.rawStatus()).isNull();
        verify(bank, times(1)).queryRefund("R-4");
    }

    @Test
    @DisplayName("★★ 没装的渠道：与旧路由表同样直接失败，不回落到别的渠道")
    void unwiredChannelFails() {
        assertThat(bridge.supports("NO_SUCH")).isFalse();
        assertThatThrownBy(() -> bridge.query("NO_SUCH", "X")).isInstanceOf(BizException.class);
        assertThat(bridge.supportsRefundCurrency("TEST", "CNY")).isTrue();
        assertThat(bridge.supportsRefundCurrency("TEST", "USD")).isFalse();
        verify(bank, times(0)).queryRefund(anyString());
    }

    private LegacyChannelBridge bridge(InProcessChannel... channels) {
        CallLogStore store = new CallLogStore() {
            @Override
            public Long insert(PayChannelCallLog row) {
                rows.add(row);
                return (long) rows.size();
            }

            @Override
            public void update(PayChannelCallLog row) {
            }

            @Override
            public Optional<PayChannelCallLog> lastSucceeded(String c, String o, String n) {
                return Optional.empty();
            }
        };
        ChannelProductCatalog catalog = new ChannelProductCatalog() {
            @Override
            public Set<ChannelKey> enabledPayKeys() {
                return Set.of();
            }

            @Override
            public boolean payEnabled(ChannelKey key) {
                return true;
            }
        };
        ChannelCapabilityRegistry caps = new ChannelCapabilityRegistry(List.of(channels));
        ChannelHandlerRegistry handlers = new ChannelHandlerRegistry(caps.handlers(), catalog);
        var invoker = new ChannelInvoker(handlers, List.of(new ValidationInterceptor(),
                new RecordingInterceptor(new ChannelCallRecorder(store)), new RetryInterceptor(new FaultPolicyRegistry()),
                new ClassifyInterceptor(new OutcomeClassifier(List.of()))), List.of());
        return new LegacyChannelBridge(invoker, handlers, caps, new ChannelContextFactory(caps, catalog));
    }
}
