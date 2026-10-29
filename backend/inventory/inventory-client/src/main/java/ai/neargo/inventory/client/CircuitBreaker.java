package ai.neargo.inventory.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 最小的熔断：连续 {@code threshold} 次传输失败（连不上、超时、5xx）就断开 {@code openFor}，
 * 期间直接失败不发请求；到点放一个请求试探，成功即合上。业务错误（4xx）不计 —— 那是请求的问题，不是服务的。
 *
 * <p>为什么要它：下单路径上库存服务挂了，没有熔断时每一单都要等满超时才失败，
 * 下单线程被拖住、连带别的请求变慢。<b>熔断即拒单</b>（Q7）：库存说不清有没有货，就不卖。
 */
public final class CircuitBreaker {

    private final int threshold;
    private final Duration openFor;
    private final Clock clock;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicReference<Instant> openUntil = new AtomicReference<>();

    public CircuitBreaker(int threshold, Duration openFor, Clock clock) {
        this.threshold = threshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    /** @return false = 熔断中，不要发请求 */
    public boolean allow() {
        Instant until = openUntil.get();
        if (until == null) {
            return true;
        }
        if (clock.instant().isBefore(until)) {
            return false;
        }
        // 半开：只放第一个试探请求过去，其余的继续拒
        return openUntil.compareAndSet(until, clock.instant().plus(openFor));
    }

    public void onSuccess() {
        failures.set(0);
        openUntil.set(null);
    }

    public void onFailure() {
        if (failures.incrementAndGet() >= threshold) {
            openUntil.set(clock.instant().plus(openFor));
        }
    }

    public boolean open() {
        Instant until = openUntil.get();
        return until != null && clock.instant().isBefore(until);
    }
}
