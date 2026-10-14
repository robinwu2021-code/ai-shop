package ai.neargo.shop.unit;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.common.ratelimit.RateLimiter;
import ai.neargo.shop.common.ratelimit.RateRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 发码限流的**第四维：按发起人**。
 *
 * <p>原来的三道限的是「发给谁」（手机号）和「从哪来」（IP），唯独没有限
 * 「谁在发」。短信轰炸正是这个形状：一个脚本对着不同号码轮着发，
 * **每个号都在自己的额度内**，IP 那道换个网络就绕开 ——
 * 而受害者是被发的那些号，他们各自只收到一两条，从任何单一维度看都不异常。
 *
 * <p>所以这条用例的核心不是「超了会拒」，而是
 * <b>「换号码不能重置计数」</b> —— 那正是前三道漏掉的那件事。
 */
class OtpSenderLimitTest {

    /** 计数型假限流器：只按 key 记次数，与真实现同语义、不引 Redis */
    private static final class CountingLimiter implements RateLimiter {
        private final Map<String, Integer> hits = new HashMap<>();

        @Override
        public Decision tryAcquire(String key, RateRule rule) {
            int n = hits.merge(key, 1, Integer::sum);
            return n <= rule.limit()
                    ? new Decision(true, Duration.ZERO)
                    : new Decision(false, Duration.ofSeconds(60));
        }

        @Override
        public void reset(String key) {
            hits.remove(key);
        }
    }

    private static OtpSendGuard guard(CountingLimiter l) {
        // 每号间隔 1 秒（用例都换号，不会撞它）；每号每日 10、每 IP 每小时 20、每发起人每日 3
        return new OtpSendGuard(l, true, 1, 10, 20, 3);
    }

    @Test
    @DisplayName("★★★ 换号码不能重置计数 —— 这正是前三道漏掉的那件事")
    void senderQuotaSurvivesPhoneRotation() {
        CountingLimiter l = new CountingLimiter();
        OtpSendGuard g = guard(l);
        // 同一个发起人，每次换一个新号码：按号码的额度永远是新的
        for (int i = 0; i < 3; i++) {
            int n = i;
            assertThatCode(() -> g.check("1770000000" + n, "U-attacker"))
                    .as("第 %s 次（换号）应当放行", n + 1).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> g.check("17700000099", "U-attacker"))
                .as("第 4 次换号仍要被挡住 —— 挡的是人，不是号码")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.OTP_DAILY_LIMIT);
    }

    @Test
    @DisplayName("★★ 不同发起人互不影响 —— 挡得太宽会牵连正常用户")
    void quotaIsPerSender() {
        CountingLimiter l = new CountingLimiter();
        OtpSendGuard g = guard(l);
        for (int i = 0; i < 3; i++) g.check("1770000001" + i, "U-a");
        assertThatCode(() -> g.check("17700000002", "U-b")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★★ 发起人被挡时，不该消耗受害号码的额度")
    void rejectingSenderDoesNotBurnVictimQuota() {
        CountingLimiter l = new CountingLimiter();
        OtpSendGuard g = guard(l);
        for (int i = 0; i < 3; i++) g.check("1770000000" + i, "U-attacker");
        assertThatThrownBy(() -> g.check("17700000088", "U-attacker")).isInstanceOf(BizException.class);
        /*
         * 受害号码 …88 一次都没真发出去，它的每日额度必须还是满的 ——
         * 否则攻击者被挡住，代价却记在受害者头上，而那个人接下来
         * 自己想收验证码时会莫名其妙被拒。
         */
        CountingLimiter l2 = new CountingLimiter();
        assertThat(l2.tryAcquire("otp:daily:17700000088", RateRule.of("d", Duration.ofDays(1), 10)).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("★★ 没有发起人（B 端登录页）→ 跳过这一维，不误伤")
    void skipsWhenNoSender() {
        CountingLimiter l = new CountingLimiter();
        OtpSendGuard g = guard(l);
        for (int i = 0; i < 5; i++) {
            int n = i;
            assertThatCode(() -> g.check("1770000000" + n, null)).doesNotThrowAnyException();
        }
    }
}
