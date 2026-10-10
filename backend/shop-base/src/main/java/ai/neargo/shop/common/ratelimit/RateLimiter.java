package ai.neargo.shop.common.ratelimit;

import java.time.Duration;

/**
 * 频率闸。**基础设施而不是业务**，所以落 {@code shop-base}——
 * 三套账号池（C/B/运营）共用，见安全整改方案 §2.3。
 *
 * <p><b>为什么返回「还要等多久」而不是只返回布尔</b>：超限之后前端要显示
 * 「60 秒后可重发」的倒计时。只给布尔的话，调用方只能自己再算一遍窗口，
 * 而它算的与闸内部算的是两套时钟——倒计时走完了仍然被拒，用户会反复点。
 */
public interface RateLimiter {

    /**
     * 尝试占用一次配额。
     *
     * <p><b>只有允许时才计数</b>：被拒的那次不该把窗口继续往后推，
     * 否则一个疯狂重试的客户端会把自己永久锁死，而正常用户等满窗口就能恢复。
     *
     * @param key  计数主体。**调用方负责加前缀**（如 {@code otp:send:phone:138...}）——
     *             不同规则共用同一个 key 会互相污染
     * @param rule 规则
     */
    Decision tryAcquire(String key, RateRule rule);

    /** 清掉某个 key 的计数。验码成功后重置失败计数用。 */
    void reset(String key);

    /**
     * @param allowed    是否放行
     * @param retryAfter 还要等多久才可能放行。allowed 为 true 时是 {@link Duration#ZERO}
     */
    record Decision(boolean allowed, Duration retryAfter) {

        public static Decision ok() {
            return new Decision(true, Duration.ZERO);
        }

        public static Decision denied(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }
    }
}
