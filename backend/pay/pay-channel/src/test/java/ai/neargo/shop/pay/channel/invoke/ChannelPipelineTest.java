package ai.neargo.shop.pay.channel.invoke;

import static ai.neargo.shop.pay.channel.invoke.Pipelines.all;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.invoker;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.pay;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.payKey;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.query;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.refund;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.wechat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.api.ChannelTransportException.Kind;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import ai.neargo.shop.pay.channel.fault.FaultPolicy;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.invoke.Pipelines.Catalog;
import ai.neargo.shop.pay.channel.invoke.Pipelines.ScriptedHandler;
import ai.neargo.shop.pay.channel.invoke.interceptor.AvailabilityInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.BulkheadInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.CircuitBreakerInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ClassifyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RetryInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TimeoutInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TraceInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ValidationInterceptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 渠道管线（TDD-支付服务-I2渠道统一封装 S2 · AC2–AC6；设计 §17） */
class ChannelPipelineTest {

    private static final Map<String, Object> SDK = Map.of("prepay_id", "wx1");

    private static ScriptedHandler handler(java.util.function.Function<Integer, ChannelResult> script) {
        return new ScriptedHandler(all("WECHAT", AccessMode.MERCHANT_OWN), script);
    }

    private static Catalog enabled() {
        return new Catalog(Set.of(payKey()));
    }

    @Nested
    @DisplayName("AC2 · InterceptorOrderTest")
    class InterceptorOrder {

        @Test
        @DisplayName("★★★ 管线顺序与注入顺序无关：追踪 → 校验 → 可用性 → 熔断 → 隔离 → 重试 → 超时 → 分类（最内层）")
        void fixedOrderRegardlessOfInjection() {
            var invoker = invoker(handler(n -> new ChannelResult.Pending(SDK, null)), enabled(), new FaultPolicyRegistry());
            assertThat(invoker.interceptors()).extracting(i -> i.getClass().getSimpleName()).containsExactly(
                    "TraceInterceptor", "ValidationInterceptor", "AvailabilityInterceptor", "CircuitBreakerInterceptor",
                    "BulkheadInterceptor", "RetryInterceptor", "TimeoutInterceptor", "ClassifyInterceptor");
        }

        @Test
        @DisplayName("★★ 两个拦截器同一序号 → 构造失败，不靠注入顺序碰运气")
        void duplicateOrderFailsFast() {
            var p = new FaultPolicyRegistry();
            var registry = new ChannelHandlerRegistry(List.of(handler(n -> null)), enabled());
            assertThatThrownBy(() -> new ChannelInvoker(registry,
                    List.of(new RetryInterceptor(p), new RetryInterceptor(p)), List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("同一序号");
        }
    }

    @Nested
    @DisplayName("AC3 · HandlerRegistryStartupTest")
    class Registry {

        @Test
        @DisplayName("★★★ 同一个键被两个同等具体的处理器命中 → 启动失败")
        void ambiguousHandlersFailStartup() {
            var a = handler(n -> null);
            var b = handler(n -> null);
            assertThatThrownBy(() -> new ChannelHandlerRegistry(List.of(a, b), new Catalog(Set.of())))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("同等具体");
        }

        @Test
        @DisplayName("★★★ 运营开着、代码没实现的产品 → 启动失败")
        void enabledProductWithoutHandlerFailsStartup() {
            var alipay = new ChannelKey("CN", "ALIPAY", AccessMode.SERVICE_PROVIDER, PayScene.ONLINE_SDK, ChannelOperation.PAY);
            assertThatThrownBy(() -> new ChannelHandlerRegistry(List.of(handler(n -> null)), new Catalog(Set.of(alipay))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ALIPAY");
        }

        @Test
        @DisplayName("★★ 最具体者优先：精确地区 + 精确场景的处理器压过任意地区 / ANY 场景的")
        void mostSpecificWins() {
            var generic = handler(n -> new ChannelResult.Pending(Map.of("who", "generic"), null));
            var exact = new ScriptedHandler(Set.of(new ChannelKey.Pattern("CN", "WECHAT", AccessMode.MERCHANT_OWN,
                    PayScene.ONLINE_SDK, ChannelOperation.PAY)), n -> new ChannelResult.Pending(Map.of("who", "exact"), null));
            var registry = new ChannelHandlerRegistry(List.of(generic, exact), enabled());
            assertThat(registry.resolve(payKey())).isSameAs(exact);
        }

        @Test
        @DisplayName("★★ 没有处理器 → 明确失败 CHANNEL_UNSUPPORTED、不可重试；不是 NPE，也不走别的渠道")
        void unknownKeyFailsExplicitly() {
            var invoker = invoker(handler(n -> null), enabled(), new FaultPolicyRegistry());
            var payby = new ai.neargo.shop.pay.channel.api.ChannelAccountContext("AE", "PAYBY", AccessMode.PLATFORM_SUB,
                    1L, "PM-1", null, "AED", 2);
            var r = invoker.execute(payby, query());
            assertThat(r).isInstanceOfSatisfying(ChannelResult.Failed.class, f -> {
                assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_UNSUPPORTED);
                assertThat(f.retryable()).isFalse();
            });
        }
    }

    @Nested
    @DisplayName("AC4 · FaultPolicyTest")
    class Fault {

        @Test
        @DisplayName("★★★ non_idempotent_operations_never_retry：退款超时只发一次，结果 Unknown（交补查），不是失败")
        void refundNeverResent() {
            var h = handler(n -> { throw new ChannelTransportException(Kind.TIMEOUT, "read timed out"); });
            var r = invoker(h, enabled(), new FaultPolicyRegistry()).execute(wechat(1), refund(30, 100));
            assertThat(h.calls).hasValue(1);
            assertThat(r).isInstanceOf(ChannelResult.Unknown.class);
            for (ChannelOperation op : List.of(ChannelOperation.REFUND, ChannelOperation.CONFIRM,
                    ChannelOperation.VOID, ChannelOperation.PAYOUT)) {
                FaultPolicy p = new FaultPolicyRegistry().policyFor("WECHAT", op);
                assertThat(p.maxRetries()).as(op.name()).isZero();
                assertThat(p.retryOnUncertain()).as(op.name()).isFalse();
            }
        }

        @Test
        @DisplayName("★★★ config_can_only_tighten：配置放宽超时 / 给退款配重试 → 拒；收紧可以")
        void configCanOnlyTighten() {
            var registry = new FaultPolicyRegistry();
            var refundDefault = registry.policyFor("WECHAT", ChannelOperation.REFUND);
            assertThatThrownBy(() -> registry.override("WECHAT", ChannelOperation.REFUND,
                    new FaultPolicy(refundDefault.readTimeout(), 2, false, Duration.ZERO, 1, 50f, 20, Duration.ofSeconds(30))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("只能收紧");
            var queryDefault = registry.policyFor("WECHAT", ChannelOperation.QUERY);
            assertThatThrownBy(() -> registry.override("WECHAT", ChannelOperation.QUERY,
                    new FaultPolicy(queryDefault.readTimeout().plusSeconds(5), 1, true, Duration.ZERO, 1, 50f, 20, Duration.ofSeconds(30))))
                    .isInstanceOf(IllegalArgumentException.class);
            registry.override("WECHAT", ChannelOperation.QUERY,
                    new FaultPolicy(Duration.ofMillis(500), 1, true, Duration.ZERO, 4, 50f, 20, Duration.ofSeconds(30)));
            assertThat(registry.policyFor("WECHAT", ChannelOperation.QUERY).maxRetries()).isEqualTo(1);
        }

        @Test
        @DisplayName("★★ 查单结果不明可以再问：最多 3 次重试；请求没发出去的收款可以再发 1 次")
        void readOnlyAndNeverSentAreRetried() {
            var q = handler(n -> n < 3 ? new ChannelResult.Unknown("5xx") : new ChannelResult.Pending(SDK, "T1"));
            assertThat(invoker(q, enabled(), new FaultPolicyRegistry()).execute(wechat(1), query()))
                    .isInstanceOf(ChannelResult.Pending.class);
            assertThat(q.calls).hasValue(3);

            var p = handler(n -> n == 1 ? new ChannelResult.Failed(PayErrorCode.CHANNEL_CONNECT_FAILED, null, "refused", true)
                    : new ChannelResult.Pending(SDK, "T1"));
            assertThat(invoker(p, enabled(), new FaultPolicyRegistry()).execute(wechat(1), pay(100, "CNY")))
                    .isInstanceOf(ChannelResult.Pending.class);
            assertThat(p.calls).hasValue(2);
        }

        @Test
        @DisplayName("★★ 超时 = Unknown，不是失败")
        void timeoutIsUnknown() {
            var registry = new FaultPolicyRegistry();
            registry.override("WECHAT", ChannelOperation.PAY,
                    new FaultPolicy(Duration.ofMillis(50), 0, false, Duration.ZERO, 4, 50f, 20, Duration.ofSeconds(30)));
            var slow = handler(n -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChannelResult.Pending(SDK, "T1");
            });
            assertThat(invoker(slow, enabled(), registry).execute(wechat(1), pay(100, "CNY")))
                    .isInstanceOfSatisfying(ChannelResult.Unknown.class, u -> assertThat(u.reason()).startsWith("TIMEOUT"));
        }
    }

    @Nested
    @DisplayName("AC5 · OutcomeClassifierTest（逐行对应设计 §8.2）")
    class Classifier {

        private final ChannelErrorMapper wechat = new ChannelErrorMapper() {
            @Override
            public String payChannel() {
                return "WECHAT";
            }

            @Override
            public Optional<MappedError> map(ChannelOperation op, int http, String code) {
                return "NOT_ENOUGH".equals(code) ? Optional.of(new MappedError(PayErrorCode.REFUND_REJECTED, false))
                        : Optional.empty();
            }
        };
        private final OutcomeClassifier c = new OutcomeClassifier(List.of(wechat));

        @Test
        @DisplayName("★★★ 超时、5xx、验签失败、无法解析、未登记渠道码一律 Unknown；连接失败 Failed(retryable)；已登记码按映射")
        void table() {
            assertThat(c.classify("WECHAT", ChannelOperation.REFUND, new ChannelTransportException(Kind.TIMEOUT, "t")))
                    .isInstanceOf(ChannelResult.Unknown.class);
            assertThat(c.classify("WECHAT", ChannelOperation.REFUND,
                    new ChannelTransportException(Kind.SERVER_ERROR, 502, null, "bad gateway", null)))
                    .isInstanceOf(ChannelResult.Unknown.class);
            assertThat(c.classify("WECHAT", ChannelOperation.REFUND, new ChannelTransportException(Kind.BAD_SIGNATURE, "x")))
                    .isInstanceOf(ChannelResult.Unknown.class);
            assertThat(c.classify("WECHAT", ChannelOperation.REFUND, new ChannelTransportException(Kind.UNPARSEABLE, "x")))
                    .isInstanceOf(ChannelResult.Unknown.class);
            assertThat(c.classify("WECHAT", ChannelOperation.REFUND,
                    new ChannelTransportException(Kind.CLIENT_ERROR, 400, "NEVER_SEEN", "?", null)))
                    .as("没见过的码当失败，退款就可能被重发").isInstanceOf(ChannelResult.Unknown.class);
            assertThat(c.classify("WECHAT", ChannelOperation.REFUND,
                    new ChannelTransportException(Kind.CLIENT_ERROR, 400, "NOT_ENOUGH", "余额不足", null)))
                    .isInstanceOfSatisfying(ChannelResult.Failed.class, f -> {
                        assertThat(f.code()).isEqualTo(PayErrorCode.REFUND_REJECTED);
                        assertThat(f.retryable()).isFalse();
                    });
            assertThat(c.classify("WECHAT", ChannelOperation.PAY, new ChannelTransportException(Kind.CONNECT_FAILED, "refused")))
                    .isInstanceOfSatisfying(ChannelResult.Failed.class, f -> assertThat(f.retryable()).isTrue());
            assertThat(c.classify("WECHAT", ChannelOperation.PAY, new NullPointerException()))
                    .isInstanceOf(ChannelResult.Unknown.class);
        }
    }

    @Nested
    @DisplayName("AC6 · CircuitBreakerTest")
    class Breaker {

        @Test
        @DisplayName("★★★ per_merchant_isolation：商户 1 熔断后，同渠道商户 2 照常发；商户 1 的查单仍放行")
        void perMerchantIsolation() {
            var h = handler(n -> new ChannelResult.Unknown("5xx"));
            var invoker = invoker(h, enabled(), new FaultPolicyRegistry());
            for (int i = 0; i < 25; i++) {
                invoker.execute(wechat(1), pay(100, "CNY"));
            }
            int before = h.calls.get();
            assertThat(invoker.execute(wechat(1), pay(100, "CNY")))
                    .isInstanceOfSatisfying(ChannelResult.Failed.class, f -> assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_UNAVAILABLE));
            assertThat(h.calls).as("熔断打开时不该再打到渠道").hasValue(before);
            invoker.execute(wechat(2), pay(100, "CNY"));
            assertThat(h.calls).as("商户 2 不受商户 1 熔断影响").hasValue(before + 1);
            invoker.execute(wechat(1), query());
            assertThat(h.calls.get()).as("查单要放行：结果不明的单还得靠它收尾").isGreaterThan(before + 1);
        }
    }

    @Nested
    @DisplayName("校验与可用性（设计 §5.2 序 20 / 30）")
    class Guards {

        @Test
        @DisplayName("★★★ 金额按渠道精度换算有余数 → 不发渠道（TWD 精度 0 时 120.50 发不出去）")
        void scaleRemainderNeverSent() {
            var h = handler(n -> new ChannelResult.Pending(SDK, null));
            var tw = new ai.neargo.shop.pay.channel.api.ChannelAccountContext("TW", "WECHAT", AccessMode.MERCHANT_OWN,
                    1L, "PM-1", null, "TWD", 0);
            var key = new ChannelKey("TW", "WECHAT", AccessMode.MERCHANT_OWN, PayScene.ONLINE_SDK, ChannelOperation.PAY);
            var r = invoker(h, new Catalog(Set.of(payKey(), key)), new FaultPolicyRegistry()).execute(tw, pay(12_050, "TWD"));
            assertThat(r).isInstanceOfSatisfying(ChannelResult.Failed.class,
                    f -> assertThat(f.code()).isEqualTo(PayErrorCode.PARAM_AMOUNT_SCALE));
            assertThat(h.calls).hasValue(0);
        }

        @Test
        @DisplayName("★★ 币种与地区不符、退款超过原单 → 不发渠道")
        void currencyAndRefundBounds() {
            var h = handler(n -> new ChannelResult.Pending(SDK, null));
            var inv = invoker(h, enabled(), new FaultPolicyRegistry());
            assertThat(inv.execute(wechat(1), pay(100, "USD"))).isInstanceOf(ChannelResult.Failed.class);
            assertThat(inv.execute(wechat(1), refund(101, 100))).isInstanceOf(ChannelResult.Failed.class);
            assertThat(h.calls).hasValue(0);
        }

        @Test
        @DisplayName("★★ 产品停用只拦新收款；退款、查单照常 —— 停一个产品不能把历史订单的退款冻住")
        void disabledProductOnlyBlocksNewPayments() {
            var h = handler(n -> new ChannelResult.Pending(SDK, null));
            var inv = invoker(h, new Catalog(Set.of()), new FaultPolicyRegistry());
            assertThat(inv.execute(wechat(1), pay(100, "CNY")))
                    .isInstanceOfSatisfying(ChannelResult.Failed.class, f -> assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_DISABLED));
            inv.execute(wechat(1), refund(30, 100));
            inv.execute(wechat(1), query());
            assertThat(h.calls).hasValue(2);
        }
    }
}
