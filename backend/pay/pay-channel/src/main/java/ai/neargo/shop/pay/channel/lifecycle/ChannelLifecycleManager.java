package ai.neargo.shop.pay.channel.lifecycle;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.InvocationListener;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 关闭排空（渠道接入统一封装设计 §10；TDD-支付服务-I2渠道统一封装 S6）。
 *
 * <p>发版重启时，正在等渠道回执的调用会被直接掐断：钱可能已经动了，而本机什么都没记下结论 —— 那就是一笔「未知」，
 * 却没有任何东西去查它。这里在关闭时先停收新调用、等在途的跑完；等不完的，按「未知」登记补查后再放行关闭。
 *
 * <p>相位排在 Web 优雅关闭之后（{@code DEFAULT_PHASE - 1536}）：先不收新请求、等请求跑完，
 * 剩下的是定时任务发起的调用，再在这里排空。
 */
public final class ChannelLifecycleManager implements SmartLifecycle, InvocationListener, BooleanSupplier {

    private static final Logger log = LoggerFactory.getLogger(ChannelLifecycleManager.class);
    /** 应用级状态变化不属于某个渠道：渠道码记作 {@code *} */
    static final String ALL = "*";

    private final Set<Invocation> inFlight = ConcurrentHashMap.newKeySet();
    private final List<InvocationListener> onAbandoned;
    private final LifecycleLogStore logs;
    private final Duration drainTimeout;
    /** 构造即收：容器 start 之前的调用（启动期任务、预热）不该被拒 */
    private volatile boolean accepting = true;
    private volatile boolean running;

    /**
     * @param onAbandoned 排空超时、仍在途的调用以「未知」通知给它们（补查登记就挂在这里）
     */
    public ChannelLifecycleManager(List<InvocationListener> onAbandoned, LifecycleLogStore logs, Duration drainTimeout) {
        this.onAbandoned = List.copyOf(onAbandoned);
        this.logs = logs;
        this.drainTimeout = drainTimeout;
    }

    @Override
    public boolean getAsBoolean() {
        return accepting;
    }

    @Override
    public void attemptStarted(Invocation inv) {
        if (inv.attempt() == 1) {
            inFlight.add(inv);
        }
    }

    @Override
    public void invocationEnded(Invocation inv, ChannelResult result) {
        if (inFlight.remove(inv)) {
            synchronized (inFlight) {
                inFlight.notifyAll();
            }
        }
    }

    @Override
    public void start() {
        accepting = true;
        running = true;
    }

    @Override
    public void stop() {
        accepting = false;
        int at = inFlight.size();
        record("ACTIVE", "DRAINING", "应用关闭：停收新调用，在途 " + at + " 笔");
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        synchronized (inFlight) {
            while (!inFlight.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    inFlight.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        List<Invocation> abandoned = List.copyOf(inFlight);
        if (abandoned.isEmpty()) {
            record("DRAINING", "STOPPED", "排空完成：" + at + " 笔在途全部有结果");
        } else {
            Set<String> what = new TreeSet<>();
            for (Invocation inv : abandoned) {
                what.add(inv.key().payChannel() + " " + inv.key().operation() + " " + inv.command().outNo());
                ChannelResult unknown = new ChannelResult.Unknown("SHUTDOWN_DRAIN_TIMEOUT");
                for (InvocationListener l : onAbandoned) {
                    try {
                        l.invocationEnded(inv, unknown);
                    } catch (RuntimeException e) {
                        log.error("[pay-lifecycle] 排空超时的调用登记补查失败 {}", inv.invocationNo(), e);
                    }
                }
            }
            log.error("[pay-lifecycle] 排空 {} 超时，{} 笔仍在途、按未知处理：{}", drainTimeout, abandoned.size(), what);
            record("DRAINING", "STOPPED", "排空超时 " + drainTimeout.toSeconds() + "s：" + abandoned.size()
                    + " 笔按未知登记补查 " + what);
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 1536;
    }

    /** 在途笔数（排障、指标） */
    public int inFlight() {
        return inFlight.size();
    }

    private void record(String from, String to, String reason) {
        log.info("[pay-lifecycle] {} → {}：{}", from, to, reason);
        try {
            logs.append(ALL, from, to, reason);
        } catch (RuntimeException e) {
            log.warn("[pay-lifecycle] 生命周期日志写不进去（关闭阶段库可能已断）：{} → {}", from, to, e);
        }
    }
}
