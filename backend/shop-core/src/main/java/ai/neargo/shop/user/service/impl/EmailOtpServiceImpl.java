package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.user.service.EmailOtpService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 邮箱登录验证码（TDD-B端邮箱验证码注册登录 · ADR-051）。
 *
 * <p>只新增这一个类：发码限流（{@link OtpSendGuard}）、过期与失败锁定（{@link OtpStore}）、
 * 邮件通道（{@link MailPort}，含发送记录装饰器）全部复用现有组件。
 *
 * <p><b>走桩必须显式放行</b>（{@code shop.mail.otp-stub-allowed}，缺省 false）。
 * 本项目生产与本地跑同一组 profile（见 {@code FixedOtpGuard} 类注释），靠名字分不开；
 * 缺省拒绝的话，生产误开桩的症状是「发码报 10505」—— 当天就会被发现，
 * 而不是「接口说发出去了，谁也没收到」。放行后验证码以 debug 打进本类日志，供本地取码。
 */
@Service
public class EmailOtpServiceImpl implements EmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MailPort mailPort;
    private final OtpStore otpStore;
    private final OtpSendGuard sendGuard;
    private final boolean mailStub;
    private final boolean stubOtpAllowed;
    private final int resendAfterSeconds;

    public EmailOtpServiceImpl(MailPort mailPort, OtpStore otpStore, OtpSendGuard sendGuard,
                           @Value("${shop.mail.stub:true}") boolean mailStub,
                           @Value("${shop.mail.otp-stub-allowed:false}") boolean stubOtpAllowed,
                           @Value("${shop.otp.interval-seconds:60}") int resendAfterSeconds) {
        this.mailPort = mailPort;
        this.otpStore = otpStore;
        this.sendGuard = sendGuard;
        this.mailStub = mailStub;
        this.stubOtpAllowed = stubOtpAllowed;
        this.resendAfterSeconds = resendAfterSeconds;
    }

    /**
     * @param locale App 当前界面语言（{@code zh-Hant} / {@code zh-Hans}）；为空按繁体（台湾一期）
     * @throws BizException 10450/10451 发码过频；10505 没有可用邮件通道或投递失败
     */
    /** 测试身份白名单（TDD-测试身份白名单登录）。setter 注入：手工构造的测试不传它 = 关 */
    private ai.neargo.shop.user.service.TestIdentityService testIdentities;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTestIdentities(ai.neargo.shop.user.service.TestIdentityService testIdentities) {
        this.testIdentities = testIdentities;
    }

    private boolean isTest(String normalized) {
        return testIdentities != null
                && testIdentities.isTestIdentity(ai.neargo.shop.user.service.TestIdentityService.EMAIL, normalized);
    }

    @Override
    public Delivery send(String email, String locale) {
        if (isTest(EmailOtpService.normalize(email))) {
            /*
             * 白名单邮箱：限流照旧，但**不发信、不存码** —— 它用的是配置里的统一码，
             * 发一封带随机码的真信只会让测试的人拿着信里的码去试（那也能过，但两套码并存只会让人糊涂）。
             * 放在 mailStub 检查之前：166 没配邮件通道时白名单照样能用。
             */
            sendGuard.check(EmailOtpService.key(EmailOtpService.normalize(email)));
            log.warn("[test-identity] 白名单邮箱 {} 发码请求已跳过（未发信）", Masks.email(EmailOtpService.normalize(email)));
            return new Delivery(OtpStore.EMAIL_TTL.toSeconds(), resendAfterSeconds);
        }
        if (mailStub && !stubOtpAllowed) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        String normalized = EmailOtpService.normalize(email);
        String key = EmailOtpService.key(normalized);
        // 限流在生成新码之前：被拦下的那次不能把用户手里那条还有效的码换掉
        sendGuard.check(key);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        Content content = Content.of(locale, code);
        try {
            mailPort.send(normalized, content.subject(), content.body(), "OTP", null);
        } catch (MailPort.MailException e) {
            // SMTP 的错误原文可能带收件人；认证边界只给 10505，且不挂 cause，免得通用日志把地址带出去
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        // 发信成功才存码：发不出去的码不该能用来登录
        otpStore.save(key, code, OtpStore.EMAIL_TTL);
        if (mailStub) {
            log.debug("[mail-stub] otp to {} = {}", Masks.email(normalized), code);
        }
        return new Delivery(OtpStore.EMAIL_TTL.toSeconds(), resendAfterSeconds);
    }

    /** @return 归一化后的地址；码不对 10455，连错锁定 10454 */
    @Override
    public String verify(String email, String code) {
        String normalized = EmailOtpService.normalize(email);
        if (testIdentities != null && testIdentities.acceptsCode(
                ai.neargo.shop.user.service.TestIdentityService.EMAIL, normalized, code)) {
            return normalized;
        }
        if (!otpStore.verifyAndConsume(EmailOtpService.key(normalized), code)) {
            throw BizException.of(ErrorCode.OTP_INVALID);
        }
        return normalized;
    }

    /**
     * 正文跟随 App 界面语言（TDD L4-D3）。后端 i18n 只有 zh-CN/en/ar，没有繁体；
     * 为两句正文补整套繁体词条会被 BackendI18nParityTest 要求补齐全部键，所以内置在这里。
     */
    record Content(String subject, String body) {
        static Content of(String locale, String code) {
            if (simplified(locale)) {
                return new Content("NearGo 商户登录验证码",
                        "您的登录验证码是 " + code + "，10 分钟内有效。\n请勿将验证码提供给任何人；如非本人操作，请忽略本邮件。");
            }
            return new Content("NearGo 商戶登入驗證碼",
                    "您的登入驗證碼是 " + code + "，10 分鐘內有效。\n請勿將驗證碼提供給任何人；如非本人操作，請忽略本郵件。");
        }

        private static boolean simplified(String locale) {
            if (locale == null) {
                return false;
            }
            String l = locale.replace('_', '-').toLowerCase(Locale.ROOT);
            return l.startsWith("zh-hans") || l.equals("zh-cn") || l.equals("zh-sg");
        }
    }
}
