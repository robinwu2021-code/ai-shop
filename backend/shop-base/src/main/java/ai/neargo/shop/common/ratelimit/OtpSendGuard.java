package ai.neargo.shop.common.ratelimit;

import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 发码前的三道闸（安全整改方案 §2.2 的 ①②③）。
 *
 * <p><b>为什么必须有</b>：{@code /mp/user/otp/send} 与 {@code /biz/auth/otp/send}
 * 都是**公网未鉴权**端点（发码必须发生在登录之前）。接上真实短信通道之后，
 * 每一次调用都是钱 —— 零限流意味着任何人循环调它就能烧掉平台的短信费。
 * 在只打日志的年代这个洞没有代价，所以它一直活到今天。
 *
 * <p><b>为什么落 shop-base</b>：发码的消费方有三处（C 端登录、B 端登录、店员登录），
 * 分属 user 与 merchant 两个域。与 {@code OtpStore} 同层同理由。
 *
 * <p><b>三道闸的分工</b>（缺一不可，且**顺序有意义**）：
 * <ol>
 *   <li>同号 60 秒一次 —— 挡误触与连点，是用户最常撞到的一道，所以第一个判，提示最具体</li>
 *   <li>同号每日 N 次 —— 挡对**某一个人**的持续骚扰</li>
 *   <li>同 IP 每小时 N 次 —— 挡换着号码刷的机器人。前两道按号计数，对它无效</li>
 * </ol>
 *
 * <p>⚠️ 当前实现是{@link InMemoryRateLimiter 进程内}的，多实例下实际阈值是「每实例 N 次」。
 * 这是已知过渡态：有闸远好过没闸。阈值按「实例数 × N」的最坏情况评估。
 */
@Component
public class OtpSendGuard {

    private static final Logger log = LoggerFactory.getLogger(OtpSendGuard.class);

    private final RateLimiter limiter;
    private final boolean enabled;
    private final RateRule perPhoneInterval;
    private final RateRule perPhoneDaily;
    private final RateRule perIpHourly;

    public OtpSendGuard(RateLimiter limiter,
                        @Value("${shop.otp.rate-limit:true}") boolean enabled,
                        @Value("${shop.otp.interval-seconds:60}") int intervalSeconds,
                        @Value("${shop.otp.daily-per-phone:10}") int dailyPerPhone,
                        @Value("${shop.otp.hourly-per-ip:20}") int hourlyPerIp) {
        this.limiter = limiter;
        this.enabled = enabled;
        this.perPhoneInterval = RateRule.of("otp.interval", Duration.ofSeconds(intervalSeconds), 1);
        this.perPhoneDaily = RateRule.of("otp.daily", Duration.ofDays(1), dailyPerPhone);
        this.perIpHourly = RateRule.of("otp.ip", Duration.ofHours(1), hourlyPerIp);

        if (!enabled) {
            /*
             * 与 {@code shop.auth.otp.fixed} 同样的护栏：**关掉它必须在日志里显眼**。
             * 集成测试要反复给同一个号发码，所以 testcfg 里关着；
             * 而它一旦被带上生产，发码端点就回到零限流 —— 那是本组件存在的全部理由。
             */
            log.warn("[DANGEROUS] shop.otp.rate-limit=false —— 发码限流已关闭。"
                    + "接了真实短信通道时，这等于把计费接口对公网敞开。**生产环境绝不能出现这条日志**");
        }
    }

    /**
     * 发码前调用。**通过才发**，超限直接抛。
     *
     * @throws BizException {@link ErrorCode#OTP_TOO_FREQUENT} / {@link ErrorCode#OTP_DAILY_LIMIT}
     *                      / {@link ErrorCode#TOO_MANY_REQUESTS}
     */
    public void check(String phone) {
        if (!enabled) {
            return;
        }
        RateLimiter.Decision interval = limiter.tryAcquire("otp:interval:" + phone, perPhoneInterval);
        if (!interval.allowed()) {
            // 秒数交给端上做倒计时按钮。至少给 1 —— 「请 0 秒后重试」是句废话
            throw BizException.of(ErrorCode.OTP_TOO_FREQUENT,
                    Math.max(interval.retryAfter().toSeconds(), 1));
        }
        if (!limiter.tryAcquire("otp:daily:" + phone, perPhoneDaily).allowed()) {
            throw BizException.of(ErrorCode.OTP_DAILY_LIMIT);
        }

        /*
         * IP 可能取不到：定时任务、内部调用、以及**过滤器没设它的链**。
         * 取不到时**放行而不是拒绝** —— 这一道是补充闸，前两道已经按号挡住了主要滥用；
         * 因为拿不到 IP 就把正常用户挡在门外，代价比漏掉一个机器人大得多。
         */
        RequestMetaContext.Meta meta = RequestMetaContext.current();
        String ip = meta == null ? null : meta.ip();
        if (ip == null || ip.isBlank()) {
            return;
        }
        RateLimiter.Decision d = limiter.tryAcquire("otp:ip:" + ip, perIpHourly);
        if (!d.allowed()) {
            // 换着号码刷的机器人只会撞这一道，所以它值一条 WARN：**这是要人去看的信号**
            log.warn("[otp] IP {} 触发发码限流（每小时 {} 次）—— 换号刷码的典型形状",
                    ip, perIpHourly.limit());
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
