package ai.neargo.shop.pay.channel.invoke;

import static ai.neargo.shop.pay.channel.invoke.Pipelines.all;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.meta;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.pay;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.query;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.wechat;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.Pipelines.ScriptedHandler;
import ai.neargo.shop.pay.channel.invoke.interceptor.RefundIntervalInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;
import ai.neargo.shop.pay.channel.log.JsonPayLogger;
import ai.neargo.shop.spi.pay.PayInstruction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管线补齐的两环（TDD-支付服务-W2渠道层补完 S7 · AC10 / AC11；I2 偏差 0）与生产管线的 13 环顺序。
 *
 * <ul>
 *   <li>指标 60：按渠道 × 操作 × 结果计数与计时 —— 熔断、补查都看结果，运维要的是同一份数字；</li>
 *   <li>退款间隔 80：同一原单两次退款不少于产品的 {@code refund_interval_seconds}（微信 60 秒）。不够就<b>排队等</b>，
 *       等不起（前面已排了一笔）明确失败、不发渠道 —— 发出去的结果是渠道拒（频率限制），而拒绝被记成「渠道说不」。</li>
 * </ul>
 */
class PipelineRingsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T08:00:00Z"));
    private final List<Duration> slept = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("★★★ 生产管线 13 环：指标(60) 在记录与日志之后、限速(80) 在幂等之后熔断之前")
    void standardOrder() {
        assertThat(standard(n -> null, 0).interceptors()).extracting(ChannelInterceptor::order)
                .containsExactly(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130);
    }

    @Test
    @DisplayName("★★★ AC10 指标：每次调用按渠道 × 操作 × 结果计一次、计时一次；不明也计")
    void metricsRing() {
        ChannelInvoker invoker = standard(n -> n == 1 ? new ChannelResult.Pending(PayInstruction.none(), "T1")
                : new ChannelResult.Unknown("TIMEOUT: x"), 0);

        invoker.execute(wechat(9), pay(100, "CNY"));
        invoker.execute(wechat(9), query());

        assertThat(meters.get("pay.channel.calls").tags("channel", "WECHAT", "operation", "PAY", "outcome", "PENDING")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.get("pay.channel.calls").tags("channel", "WECHAT", "operation", "QUERY", "outcome", "UNKNOWN")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.get("pay.channel.latency").tags("channel", "WECHAT", "operation", "PAY").timer().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ AC11 同一原单第二笔退款排队到间隔满再发；第三笔等不起 → 明确失败（可重试）且不发；别的原单不受影响")
    void refundIntervalQueues() {
        ScriptedHandler[] h = new ScriptedHandler[1];
        ChannelInvoker invoker = standard(n -> new ChannelResult.Pending(PayInstruction.none(), "R" + n), 60, h);

        invoker.execute(wechat(9), refund("OD1", "OD1-R1"));
        assertThat(slept).isEmpty();

        clock.advance(Duration.ofSeconds(10));
        ChannelResult second = invoker.execute(wechat(9), refund("OD1", "OD1-R2"));
        assertThat(second).isInstanceOf(ChannelResult.Pending.class);
        assertThat(slept).containsExactly(Duration.ofSeconds(50));

        ChannelResult third = invoker.execute(wechat(9), refund("OD1", "OD1-R3"));
        assertThat(third).isInstanceOfSatisfying(ChannelResult.Failed.class, f -> {
            assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_THROTTLED);
            assertThat(f.retryable()).isTrue();
        });
        assertThat(h[0].calls.get()).as("第三笔一个字节都不该发出去").isEqualTo(2);

        invoker.execute(wechat(9), refund("OD2", "OD2-R1"));
        assertThat(slept).as("别的原单不排队").hasSize(1);
        assertThat(h[0].calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("★★ 产品没配间隔（0）不排队；查单不受限速影响")
    void noIntervalNoQueue() {
        ChannelInvoker invoker = standard(n -> new ChannelResult.Pending(PayInstruction.none(), "R"), 0);
        invoker.execute(wechat(9), refund("OD1", "OD1-R1"));
        invoker.execute(wechat(9), refund("OD1", "OD1-R2"));
        invoker.execute(wechat(9), query());
        assertThat(slept).isEmpty();
    }

    // ---------------------------------------------------------------- 夹具

    private ChannelInvoker standard(Function<Integer, ChannelResult> script, int intervalSeconds) {
        return standard(script, intervalSeconds, null);
    }

    private ChannelInvoker standard(Function<Integer, ChannelResult> script, int intervalSeconds, ScriptedHandler[] out) {
        ScriptedHandler h = new ScriptedHandler(all("WECHAT", AccessMode.MERCHANT_OWN), script);
        if (out != null) {
            out[0] = h;
        }
        ChannelProductCatalog catalog = new ChannelProductCatalog() {
            @Override
            public Set<ChannelKey> enabledPayKeys() {
                return Set.of(Pipelines.payKey());
            }

            @Override
            public boolean payEnabled(ChannelKey key) {
                return true;
            }

            @Override
            public int refundIntervalSeconds(String payChannel, AccessMode accessMode) {
                return intervalSeconds;
            }
        };
        MemoryCallLogStore store = new MemoryCallLogStore();
        var chain = StandardPipeline.interceptors(catalog, new FaultPolicyRegistry(), new ChannelCallRecorder(store),
                new JsonPayLogger(), store, List.of(), meters,
                // 只记不拨钟：第三笔就是在第二笔还在排队时到的
                new RefundIntervalInterceptor(catalog, clock, Duration.ofSeconds(65), slept::add));
        return new ChannelInvoker(new ChannelHandlerRegistry(List.<ChannelHandler>of(h), catalog), chain, List.of());
    }

    private static RefundCommand refund(String origin, String outRefundNo) {
        return new RefundCommand(meta(), origin, "42000" + origin, new Money(1000, "CNY"), new Money(100, "CNY"),
                outRefundNo, "r");
    }

    /** 可拨的时钟 */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
