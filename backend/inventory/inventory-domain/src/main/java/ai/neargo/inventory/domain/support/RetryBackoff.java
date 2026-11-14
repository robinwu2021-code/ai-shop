package ai.neargo.inventory.domain.support;

import java.time.Duration;
import java.util.Optional;

/**
 * 事件投递的退避：10 秒起、每次翻倍、封顶 30 分钟，第 10 次失败后不再自动重试。
 *
 * <p>数值与 ai-shop 统一出站表的默认策略一致 —— 过渡期两边的积压表现要能对得上，
 * 否则同一个下游挂了，商城那边已经停了重试、这边还在打，排查时两张表讲两个故事。
 */
public final class RetryBackoff {

    /** 到这个次数就不再自动重试，交给人：让它在积压里显出来。 */
    public static final int MAX_ATTEMPTS = 10;

    private static final Duration INITIAL = Duration.ofSeconds(10);
    private static final Duration MAX = Duration.ofMinutes(30);

    private RetryBackoff() {
    }

    /** 第 {@code failedAttempt} 次失败之后等多久；空 = 不再重试。 */
    public static Optional<Duration> delayAfter(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("Attempt must be positive");
        }
        if (failedAttempt >= MAX_ATTEMPTS) {
            return Optional.empty();
        }
        long factor = 1L << Math.min(failedAttempt - 1, 30);
        long initial = INITIAL.toMillis();
        long cap = MAX.toMillis();
        return Optional.of(Duration.ofMillis(initial > cap / factor ? cap : initial * factor));
    }
}
