package ai.neargo.shop.pay.channel.invoke;

import static ai.neargo.shop.pay.channel.invoke.Pipelines.all;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.pay;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.s2Interceptors;
import static ai.neargo.shop.pay.channel.invoke.Pipelines.wechat;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.followup.FollowUpScheduler;
import ai.neargo.shop.pay.channel.followup.FollowUpStore;
import ai.neargo.shop.pay.channel.followup.FollowUpTask;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.invoke.Pipelines.Catalog;
import ai.neargo.shop.pay.channel.invoke.Pipelines.ScriptedHandler;
import ai.neargo.shop.pay.channel.lifecycle.ChannelLifecycleManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC10：关闭时在途调用排空，等不完的转「未知」补查（TDD-支付服务-I2渠道统一封装 S6）。
 *
 * <p>此前发版重启直接掐断正在等回执的调用：钱可能已经动了，本机却没有任何东西记下「这笔要查」。
 */
@DisplayName("AC10 · LifecycleDrainTest")
class LifecycleDrainTest {

    private final List<String> lifecycle = new CopyOnWriteArrayList<>();
    private final List<FollowUpTask> followUps = new CopyOnWriteArrayList<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private final FollowUpStore store = new FollowUpStore() {
        @Override public boolean open(FollowUpTask t) { return followUps.add(t); }
        @Override public List<FollowUpTask> due(Instant now, int limit) { return List.of(); }
        @Override public boolean claim(FollowUpTask t, Instant lease) { return false; }
        @Override public void resolve(FollowUpTask t, String s) { }
        @Override public void retry(FollowUpTask t, Instant next, String s) { }
        @Override public void escalate(FollowUpTask t, String s) { }
    };

    private ChannelLifecycleManager manager(Duration drain) {
        var scheduler = new FollowUpScheduler(store, Clock.systemUTC());
        var m = new ChannelLifecycleManager(List.of(scheduler),
                (ch, from, to, reason) -> lifecycle.add(ch + " " + from + "→" + to + " " + reason), drain);
        m.start();
        return m;
    }

    private ChannelInvoker invoker(ChannelLifecycleManager m) {
        var handler = new ScriptedHandler(all("WECHAT", AccessMode.MERCHANT_OWN), n -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChannelResult.Pending(Map.of(), "TX-1");
        });
        var catalog = new Catalog(Set.of(Pipelines.payKey()));
        var policies = new FaultPolicyRegistry();
        return new ChannelInvoker(new ChannelHandlerRegistry(List.of(handler), catalog),
                s2Interceptors(catalog, policies, List.of()), List.of(new FollowUpScheduler(store, Clock.systemUTC()), m), m);
    }

    @Test
    @DisplayName("★★★ 关闭：停收新调用（明确失败、没发出去）；在途那笔跑完才放行，结果照常、不登记补查")
    void drainsInFlightBeforeStopping() throws Exception {
        var m = manager(Duration.ofSeconds(5));
        var invoker = invoker(m);
        var inFlight = CompletableFuture.supplyAsync(() -> invoker.execute(wechat(1), pay(100, "CNY")));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(m.inFlight()).isEqualTo(1);

        var stopping = CompletableFuture.runAsync(m::stop);
        Thread.sleep(100);
        assertThat(stopping).as("在途没跑完，不能放行关闭").isNotDone();
        ChannelResult late = invoker.execute(wechat(2), pay(100, "CNY"));
        assertThat(late).isInstanceOfSatisfying(ChannelResult.Failed.class,
                f -> assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_UNAVAILABLE));

        release.countDown();
        stopping.get(5, TimeUnit.SECONDS);
        assertThat(inFlight.get(5, TimeUnit.SECONDS)).isInstanceOf(ChannelResult.Pending.class);
        assertThat(followUps).isEmpty();
        assertThat(lifecycle).hasSize(2);
        assertThat(lifecycle.get(1)).startsWith("* DRAINING→STOPPED 排空完成");
        assertThat(m.isRunning()).isFalse();
    }

    @Test
    @DisplayName("★★★ 排空超时：仍在途的收款按「未知」登记补查后放行关闭 —— 不能卡住发版，也不能丢下不管")
    void drainTimeoutRegistersFollowUp() throws Exception {
        var m = manager(Duration.ofMillis(200));
        var invoker = invoker(m);
        CompletableFuture.supplyAsync(() -> invoker.execute(wechat(1), pay(100, "CNY")));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        m.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(3));
        assertThat(followUps).singleElement().satisfies(t -> {
            assertThat(t.operation()).isEqualTo("PAY");
            assertThat(t.outNo()).isEqualTo("OD1");
            assertThat(t.payChannel()).isEqualTo("WECHAT");
        });
        assertThat(lifecycle.get(1)).contains("排空超时").contains("WECHAT PAY OD1");
        release.countDown();
    }
}
