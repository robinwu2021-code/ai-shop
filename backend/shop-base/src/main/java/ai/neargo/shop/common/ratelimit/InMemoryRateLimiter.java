package ai.neargo.shop.common.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内滑动窗口限流。
 *
 * <p><b>滑动窗口而不是固定窗口</b>：固定窗口在边界上会放过两倍的量
 * （59 秒发满 10 条、下一秒窗口重置又能发 10 条），而「一分钟内最多 10 条」
 * 这句话对使用者的含义是滑动的。发短信是花钱的动作，边界翻倍不是小事。
 *
 * <p><b>只有放行才计数</b>：被拒的那次不把窗口往后推。否则一个疯狂重试的客户端
 * 会把自己永久锁死——它每次被拒都在续期，而正常用户等满窗口就恢复。
 *
 * <p><b>⚠️ 进程内，多实例不共享。</b> `api` 今天就多实例水平扩，
 * 所以实际生效阈值是「每实例 N 次」。这不是设计缺陷而是**已知的过渡态**：
 * 有闸远好过没闸（今天是零限流），换 Redis 实现时只改这一个类。
 * 阈值按最坏情况（实例数 × N）评估，别按 N 评估。
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    /**
     * key 数量上限。**这不是性能优化，是防打挂自己**——
     * key 里含手机号与 IP，攻击者换号就能无限造 key，不设上限等于给了一条内存耗尽的路。
     * 超限时整表清一次：宁可短暂放宽，也不能把进程撑死。
     */
    private static final int MAX_KEYS = 100_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter() {
        this(Clock.systemUTC());
    }

    /** 测试注入固定时钟：验窗口边界不该靠 {@code Thread.sleep} —— 那是慢且不稳的。 */
    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, RateRule rule) {
        long now = clock.millis();
        long windowMs = rule.window().toMillis();

        if (hits.size() > MAX_KEYS) {
            sweep(now);
            if (hits.size() > MAX_KEYS) {
                hits.clear();
            }
        }

        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= windowMs) {
                q.pollFirst();
            }
            if (q.size() >= rule.limit()) {
                // 最早那次滑出窗口的时刻 = 下一次可能被放行的时刻
                long waitMs = windowMs - (now - q.peekFirst());
                return Decision.denied(Duration.ofMillis(Math.max(waitMs, 0)));
            }
            q.addLast(now);
            return Decision.ok();
        }
    }

    @Override
    public void reset(String key) {
        hits.remove(key);
    }

    /** 清掉窗口早已滑过的 key。窗口长度未知，用一天兜底——比它长的规则本方案里没有。 */
    private void sweep(long now) {
        hits.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                return q.isEmpty() || now - q.peekLast() >= Duration.ofDays(1).toMillis();
            }
        });
    }
}
