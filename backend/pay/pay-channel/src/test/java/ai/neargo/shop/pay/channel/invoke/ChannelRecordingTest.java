package ai.neargo.shop.pay.channel.invoke;

import static ai.neargo.shop.pay.channel.invoke.Pipelines.all;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.pay;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.payKey;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.query;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.refund;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.wechat;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.invoke.Pipelines.Catalog;
import ai.neargo.shop.pay.channel.invoke.Pipelines.ScriptedHandler;
import ai.neargo.shop.pay.channel.invoke.interceptor.IdempotencyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.LoggingInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RecordingInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;
import ai.neargo.shop.pay.channel.log.JsonPayLogger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 调用记录、结构化日志与幂等（TDD-支付服务-I2渠道统一封装 S3 · AC7；设计 §11.2、§17 AmountTraceTest / SourceTraceTest） */
class ChannelRecordingTest {

    private final MemoryCallLogStore store = new MemoryCallLogStore();
    private final ChannelCallRecorder recorder = new ChannelCallRecorder(store);

    private ChannelInvoker invoker(Function<Integer, ChannelResult> script, ScriptedHandler[] out, Set<ChannelKey> enabled) {
        ScriptedHandler h = new ScriptedHandler(all("WECHAT", AccessMode.MERCHANT_OWN), script);
        if (out != null) {
            out[0] = h;
        }
        var catalog = new Catalog(enabled);
        var policies = new FaultPolicyRegistry();
        var chain = Pipelines.s2Interceptors(catalog, policies, List.of());
        chain.add(new RecordingInterceptor(recorder));
        chain.add(new LoggingInterceptor(new JsonPayLogger()));
        chain.add(new IdempotencyInterceptor(store));
        return new ChannelInvoker(new ChannelHandlerRegistry(List.of(h), catalog), chain, List.of());
    }

    @Test
    @DisplayName("★★★ 管线 11 环顺序：记录(40) 在发送前、日志(50)、幂等(70) 在熔断之外")
    void fullOrder() {
        assertThat(invoker(n -> null, null, Set.of(payKey())).interceptors()).extracting(i -> i.order())
                .containsExactly(10, 20, 30, 40, 50, 70, 90, 100, 110, 120, 130);
    }

    @Test
    @DisplayName("★★★ AmountTraceTest：请求金额、按渠道精度的金额字符串、响应金额、手续费都落库")
    void amountTrace() {
        invoker(n -> new ChannelResult.Succeeded("4200001", new Money(1234, "CNY"), 7L, Map.of()), null, Set.of(payKey()))
                .execute(wechat(9), pay(1234, "CNY"));
        PayChannelCallLog r = store.rows.getFirst();
        assertThat(r.getRequestAmountMinor()).isEqualTo(1234L);
        assertThat(r.getRequestCurrency()).isEqualTo("CNY");
        assertThat(r.getChannelAmountText()).isEqualTo("12.34");
        assertThat(r.getAmountScale()).isEqualTo(2);
        assertThat(r.getResponseAmountMinor()).isEqualTo(1234L);
        assertThat(r.getResponseCurrency()).isEqualTo("CNY");
        assertThat(r.getFeeMinor()).isEqualTo(7L);
        assertThat(r.getOutcomeClass()).isEqualTo("SUCCEEDED");
        assertThat(r.getChannelTradeNo()).isEqualTo("4200001");

        var tw = new ChannelAccountContext("TW", "WECHAT", AccessMode.MERCHANT_OWN, 1L, "PM-1", null, "TWD", 0);
        var twKey = new ChannelKey("TW", "WECHAT", AccessMode.MERCHANT_OWN, PayScene.ONLINE_SDK, ChannelOperation.PAY);
        invoker(n -> new ChannelResult.Pending(Map.of(), null), null, Set.of(twKey)).execute(tw, pay(120_000, "TWD"));
        assertThat(store.rows.getLast().getChannelAmountText()).as("TWD 精度 0：发给渠道的是整数元").isEqualTo("1200");
    }

    @Test
    @DisplayName("★★★ SourceTraceTest：追踪号、来源两维、关联 id、我方单号逐字落库")
    void sourceTrace() {
        var cmd = pay(100, "CNY");
        invoker(n -> new ChannelResult.Pending(Map.of(), "T1"), null, Set.of(payKey())).execute(wechat(9), cmd);
        PayChannelCallLog r = store.rows.getFirst();
        assertThat(r.getTraceId()).isEqualTo(cmd.meta().traceId()).isNotBlank();
        assertThat(r.getSourceType()).isEqualTo("INTERNAL");
        assertThat(r.getClientType()).isEqualTo("SERVER");
        assertThat(r.getPayOrderId()).isEqualTo(1L);
        assertThat(r.getStlPaymentId()).isEqualTo(2L);
        assertThat(r.getPayMerchantId()).isEqualTo(9L);
        assertThat(r.getRefType()).isEqualTo("PAY_ORDER");
        assertThat(r.getOutTradeNo()).isEqualTo("OD1");
        assertThat(r.getScene()).isEqualTo("ONLINE_SDK");
        assertThat(r.getOperation()).isEqualTo("PAY");
        assertThat(r.getFaultPolicy()).contains("timeout=");
        assertThat(r.getBreakerState()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★★★ 重试每次一行：同一调用号，attempt 递增，retry_of_id 指向上一行，各自的结果分类")
    void retryRowPerAttempt() {
        invoker(n -> n < 3 ? new ChannelResult.Unknown("HTTP_502") : new ChannelResult.Pending(Map.of(), "T1"), null,
                Set.of(payKey())).execute(wechat(9), query());
        assertThat(store.rows).hasSize(3);
        assertThat(store.rows).extracting(PayChannelCallLog::getAttempt).containsExactly(1, 2, 3);
        assertThat(store.rows).extracting(PayChannelCallLog::getOutcomeClass).containsExactly("UNKNOWN", "UNKNOWN", "PENDING");
        assertThat(store.rows).extracting(PayChannelCallLog::getInvocationNo).containsOnly(store.rows.getFirst().getInvocationNo());
        assertThat(store.rows.get(1).getRetryOfId()).isEqualTo(store.rows.get(0).getId());
        assertThat(store.rows.get(2).getRetryOfId()).isEqualTo(store.rows.get(1).getId());
    }

    @Test
    @DisplayName("★★ 记录表写不进去不阻断业务：渠道照常调用、结果照常返回，失败计数")
    void recordingFailureDoesNotBlock() {
        store.broken = true;
        var out = new ScriptedHandler[1];
        var r = invoker(n -> new ChannelResult.Pending(Map.of(), "T1"), out, Set.of(payKey())).execute(wechat(9), pay(100, "CNY"));
        assertThat(r).isInstanceOf(ChannelResult.Pending.class);
        assertThat(out[0].calls).hasValue(1);
        assertThat(recorder.failures()).isPositive();
    }

    @Test
    @DisplayName("★★★ 同一退款单号已明确成功 → 不再发渠道，返回记录里的结果；查单照常问")
    void succeededRefundIsNotResent() {
        var out = new ScriptedHandler[1];
        var inv = invoker(n -> new ChannelResult.Succeeded("WX-R-" + n, new Money(30, "CNY"), null, Map.of()), out, Set.of(payKey()));
        inv.execute(wechat(9), refund(30, 100));
        var again = inv.execute(wechat(9), refund(30, 100));
        assertThat(out[0].calls).as("第二次不该打到渠道").hasValue(1);
        assertThat(again).isInstanceOfSatisfying(ChannelResult.Succeeded.class, s -> {
            assertThat(s.channelTradeNo()).isEqualTo("WX-R-1");
            assertThat(s.extra()).containsKey("replayedFromCallLog");
        });
        inv.execute(wechat(9), query());
        inv.execute(wechat(9), query());
        assertThat(out[0].calls).as("查单每次都问").hasValue(3);
    }
}
