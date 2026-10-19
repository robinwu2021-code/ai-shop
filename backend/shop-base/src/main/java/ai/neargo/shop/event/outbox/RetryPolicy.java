package ai.neargo.shop.event.outbox;

import java.time.Duration;
import java.util.Optional;

/** One retry policy for all sources; exhausted deliveries must become visible failures. */
public record RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay) {
    public RetryPolicy {
        if (maxAttempts < 1 || initialDelay.isNegative() || initialDelay.isZero()
                || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Invalid outbox retry policy");
        }
    }
    public static RetryPolicy defaults() {
        return new RetryPolicy(10, Duration.ofSeconds(10), Duration.ofMinutes(30));
    }
    public Optional<Duration> retryDelay(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("Attempt must be positive");
        }
        if (failedAttempt >= maxAttempts) {
            return Optional.empty();
        }
        long factor = 1L << Math.min(failedAttempt - 1, 30);
        long initial = initialDelay.toMillis();
        long cap = maxDelay.toMillis();
        return Optional.of(Duration.ofMillis(initial > cap / factor ? cap : initial * factor));
    }
}
