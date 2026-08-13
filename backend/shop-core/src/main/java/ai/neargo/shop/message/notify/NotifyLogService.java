package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.captcha.CaptchaService;
import ai.neargo.shop.common.ratelimit.RateLimiter;
import ai.neargo.shop.common.ratelimit.RateRule;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发送记录的查询，以及运营端的**测试发送**。
 *
 * <p><b>测试发送为什么要三道闸一起上</b>（权限码 + 图形验证码 + 限流）：
 * 它是一个能**指定任意收件人**的接口。只上权限码的话，运营账号泄漏就等于
 * 拿到一台群发机 —— 而且发出去的是带平台签名的正规短信，比垃圾短信更能骗到人。
 *
 * <ul>
 *   <li>权限码防越权（别人打不开这个页面）</li>
 *   <li><b>图形验证码防脚本化</b> —— 账号泄漏后攻击者拿到的是 token，而验证码要人眼</li>
 *   <li>限流防「一个人手工点很多次」</li>
 * </ul>
 */
@Service
public class NotifyLogService {

    /** 测试发送：同一个操作人每小时的上限。人工点几下够用，脚本刷不动 */
    private static final RateRule TEST_SEND_PER_OPERATOR =
            RateRule.of("notify.test", Duration.ofHours(1), 10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotifyLogMapper mapper;
    private final CaptchaService captcha;
    private final RateLimiter limiter;
    private final NotifyLoggingSmsPort smsPort;
    private final NotifyLoggingMailPort mailPort;
    private final boolean rateLimitOn;

    public NotifyLogService(NotifyLogMapper mapper, CaptchaService captcha, RateLimiter limiter,
                            NotifyLoggingSmsPort smsPort, NotifyLoggingMailPort mailPort,
                            @Value("${shop.otp.rate-limit:true}") boolean rateLimitOn) {
        this.mapper = mapper;
        this.captcha = captcha;
        this.limiter = limiter;
        this.smsPort = smsPort;
        this.mailPort = mailPort;
        this.rateLimitOn = rateLimitOn;
    }

    /** @param channel/status 传 null 表示不筛 */
    public PageData<SysNotifyLog> list(String channel, String status, long page, long size) {
        var q = Wrappers.<SysNotifyLog>lambdaQuery()
                .eq(channel != null && !channel.isBlank(), SysNotifyLog::getChannel, channel)
                .eq(status != null && !status.isBlank(), SysNotifyLog::getStatus, status)
                .orderByDesc(SysNotifyLog::getId);
        // 平台侧运维记录，没有数据域概念
        List<SysNotifyLog> all = DataScopeContext.executeWithoutScope(() -> mapper.selectList(q));
        return PageData.ofAll(all, page, size);
    }

    /**
     * 测试发送。**只发得出去，读不回来** —— 不返回验证码内容，
     * 否则这个接口就成了「给任意手机号发一个我知道的验证码」，那正是它要防的事。
     *
     * @param target    手机号或邮箱
     * @param captchaId 图形验证码挑战 ID
     * @param code      用户输入的图形验证码
     */
    public void testSend(String channel, String target, String captchaId, String code,
                         String operatorNo) {
        if (target == null || target.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **验证码先验**：放在限流之后的话，攻击者可以用错误的验证码把
         * 操作人的限流额度刷满 —— 一个不需要正确验证码就能实施的拒绝服务。
         */
        captcha.verifyAndConsume(captchaId, code);

        if (rateLimitOn && !limiter.tryAcquire("notify:test:" + operatorNo, TEST_SEND_PER_OPERATOR)
                .allowed()) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
        }

        if (SysNotifyLog.MAIL.equals(channel)) {
            mailPort.send(target, "【数智邻购】通道联通测试",
                    "这是一封测试邮件，用于确认邮件通道可用。\n发送时间：" + LocalDateTime.now(),
                    SysNotifyLog.BIZ_TEST, operatorNo);
        } else {
            smsPort.sendOtp(target, "%06d".formatted(RANDOM.nextInt(1_000_000)),
                    SysNotifyLog.BIZ_TEST, operatorNo);
        }
    }
}
