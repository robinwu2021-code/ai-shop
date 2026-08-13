package ai.neargo.shop.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 限流闸的边界。**用可控时钟而不是 sleep** —— sleep 版慢，且在 CI 上会偶发红。
 */
@DisplayName("发码限流闸")
class InMemoryRateLimiterTest {

    /** 可拨动的时钟。 */
    private static final class Tick extends Clock {
        private Instant now = Instant.parse("2026-08-13T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Tick tick = new Tick();
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(tick);

    private static final RateRule PER_MINUTE_3 = RateRule.of("test", Duration.ofMinutes(1), 3);

    @Test
    @DisplayName("窗口内放行到额度用尽")
    void allowsUpToLimit() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed())
                    .as("第 %d 次应放行", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed()).isFalse();
    }

    @Test
    @DisplayName("★★ 超限时给出「还要等多久」—— 否则前端倒计时只能自己再算一遍，两套时钟必然对不上")
    void reportsRetryAfter() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k", PER_MINUTE_3);
        }
        tick.advance(Duration.ofSeconds(20));

        RateLimiter.Decision d = limiter.tryAcquire("k", PER_MINUTE_3);
        assertThat(d.allowed()).isFalse();
        // 最早那次是 20 秒前，还要等 40 秒它才滑出窗口
        assertThat(d.retryAfter()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("★★★ 被拒的那次不计数 —— 否则疯狂重试的客户端会把自己永久锁死")
    void deniedAttemptsDoNotExtendWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k", PER_MINUTE_3);
        }
        /*
         * **重试必须摊在时间轴上**，不能都发生在同一时刻。
         * 第一版 50 次全撞在 t=0：破坏版塞进去的时间戳与原来那 3 条一起滑出窗口，
         * 两版结果一模一样 —— 验红时它没变红，测试等于没写。
         *
         * 每秒撞一次，撞到 t=50。若被拒也计数，窗口里最新的一条就是 t=50，
         * 到 t=61 时它还没滑出去 → 仍被拒。
         */
        for (int i = 0; i < 50; i++) {
            tick.advance(Duration.ofSeconds(1));
            assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed())
                    .as("t=%ds 的重试应被拒", i + 1).isFalse();
        }
        // 从最早那次算起满一分钟，应当恢复
        tick.advance(Duration.ofSeconds(11));
        assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed()).isTrue();
    }

    @Test
    @DisplayName("★★★ 滑动而不是固定窗口 —— 固定窗口在边界上会放过两倍的量，而发短信是花钱的")
    void slidingNotFixedWindow() {
        // 0s 用掉 3 次
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k", PER_MINUTE_3);
        }
        // 59 秒仍在窗口内 —— 固定窗口会在这里重置
        tick.advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed()).isFalse();

        // 61 秒：最早那三次刚滑出去
        tick.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed()).isTrue();
    }

    @Test
    @DisplayName("★★ key 之间互不影响 —— 一个人被限不该连累另一个人")
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a", PER_MINUTE_3);
        }
        assertThat(limiter.tryAcquire("a", PER_MINUTE_3).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", PER_MINUTE_3).allowed()).isTrue();
    }

    @Test
    @DisplayName("reset 清掉计数")
    void resetClearsCounter() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k", PER_MINUTE_3);
        }
        assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed()).isFalse();
        limiter.reset("k");
        assertThat(limiter.tryAcquire("k", PER_MINUTE_3).allowed()).isTrue();
    }

    @Test
    @DisplayName("规则参数非法在构造时就拒绝，而不是运行期才发现")
    void rejectsInvalidRule() {
        assertThrows(IllegalArgumentException.class, () -> RateRule.of("x", Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> RateRule.of("x", Duration.ofMinutes(1), 0));
    }
}
