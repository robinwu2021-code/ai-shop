package ai.neargo.shop.common.ratelimit;

import java.time.Duration;

/**
 * 一条限流规则：**窗口内最多几次**。
 *
 * @param name   规则名。只用于日志与超限提示的定位，不参与计数
 * @param window 时间窗
 * @param limit  窗口内允许的次数
 */
public record RateRule(String name, Duration window, int limit) {

    public RateRule {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static RateRule of(String name, Duration window, int limit) {
        return new RateRule(name, window, limit);
    }
}
