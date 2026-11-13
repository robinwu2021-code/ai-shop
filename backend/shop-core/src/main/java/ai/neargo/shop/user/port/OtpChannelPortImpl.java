package ai.neargo.shop.user.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.NotifyBizType;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.otp.OtpChannelPort;
import ai.neargo.shop.spi.platform.SmsAdmissionPort;
import ai.neargo.shop.user.service.OtpTestPhoneService;
import ai.neargo.shop.user.service.TestIdentityService;
import java.security.SecureRandom;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Extracted OTP channel lifecycle; account creation and captcha policy stay outside. */
@Component
public class OtpChannelPortImpl implements OtpChannelPort {
    private static final Logger log = LoggerFactory.getLogger(OtpChannelPortImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final OtpStore store;
    private final SmsPort sms;
    private final MailPort mail;
    private final SmsAdmissionPort smsAdmission;
    private final OtpTestPhoneService testPhones;
    private final TestIdentityService testIdentities;
    private final String fixedOtp;
    private final boolean mailStub, stubOtpAllowed;
    private final List<String> mockPrefixes;

    public OtpChannelPortImpl(OtpStore store, SmsPort sms, MailPort mail,
                             SmsAdmissionPort smsAdmission, OtpTestPhoneService testPhones,
                             TestIdentityService testIdentities,
                             @Value("${shop.auth.otp.fixed:}") String fixedOtp,
                             @Value("${shop.mail.stub:true}") boolean mailStub,
                             @Value("${shop.mail.otp-stub-allowed:false}") boolean stubOtpAllowed,
                             @Value("${shop.sms.mock-prefixes:}") String mockPrefixes) {
        this.store = store; this.sms = sms; this.mail = mail; this.smsAdmission = smsAdmission;
        this.testPhones = testPhones; this.testIdentities = testIdentities; this.fixedOtp = fixedOtp;
        this.mailStub = mailStub; this.stubOtpAllowed = stubOtpAllowed;
        this.mockPrefixes = mockPrefixes == null ? List.of() : Arrays.stream(mockPrefixes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (fixedOtp != null && !fixedOtp.isBlank())
            log.error("[DANGEROUS] shop.auth.otp.fixed is enabled; production must reject this configuration");
    }
    private boolean isLogin(String scene) { return OtpScenes.LOGIN.equals(scene); }
    private boolean isTest(Channel channel, String recipient) {
        return testIdentities.isTestIdentity(channel == Channel.SMS ? TestIdentityService.PHONE : TestIdentityService.EMAIL, recipient);
    }
    @Override public void requireAvailable(String scene, Channel channel, String recipient) {
        if (channel == Channel.SMS) {
            smsAdmission.requireSmsEnabled(recipient);
            if (!isLogin(scene)) sms.requireOtpScene(scene);
        } else if (mailStub && !stubOtpAllowed && !(isLogin(scene) && isTest(channel, recipient))) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
    }
    @Override public DeliveryResult deliver(DeliveryCommand c) {
        if (c.channel() == Channel.SMS) sendSms(c); else sendEmail(c);
        return new DeliveryResult(c.ttl().toSeconds());
    }
    private void sendSms(DeliveryCommand c) {
        if (isLogin(c.scene())) {
            if (fixedOtp != null && !fixedOtp.isBlank()) { store.save(c.storageKey(), fixedOtp, c.ttl()); return; }
            if (isTest(Channel.SMS, c.recipient())) {
                log.warn("[test-identity] 白名单手机号 {} 发码请求已跳过（未发短信）", Masks.phone(c.recipient()));
                return;
            }
            var fixed = testPhones.fixedCodeFor(c.recipient());
            if (fixed.isPresent()) { store.save(c.storageKey(), fixed.get(), c.ttl()); return; }
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        // Preserve SMS's original activation-before-delivery behavior.
        store.save(c.storageKey(), code, c.ttl());
        if (isLogin(c.scene()) && mockPrefixes.stream().anyMatch(c.recipient()::startsWith)) {
            log.debug("[sms-mock] otp to {} = {}", Masks.phone(c.recipient()), code);
            return;
        }
        if (isLogin(c.scene())) sms.sendOtp(c.recipient(), code);
        else sms.sendOtpForScene(c.recipient(), code, c.scene(), NotifyBizType.OTP, null);
    }
    private void sendEmail(DeliveryCommand c) {
        if (isLogin(c.scene()) && isTest(Channel.EMAIL, c.recipient())) {
            log.warn("[test-identity] 白名单邮箱 {} 发码请求已跳过（未发信）", Masks.email(c.recipient()));
            return;
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        var content = EmailContent.of(c.scene(), c.locale(), code, c.ttl().toSeconds());
        try { mail.send(c.recipient(), content.subject(), content.body(), NotifyBizType.OTP, null); }
        catch (MailPort.MailException e) { throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE); }
        // Preserve email's activation-after-success behavior and sanitized SMTP error boundary.
        store.save(c.storageKey(), code, c.ttl());
        if (mailStub) log.debug("[mail-stub] otp to {} = {}", Masks.email(c.recipient()), code);
    }
    @Override public boolean verifyAndConsume(VerificationCommand c) {
        if (c.channel() == Channel.SMS) smsAdmission.requireSmsEnabled(c.recipient());
        if (isLogin(c.scene()) && testIdentities.acceptsCode(
                c.channel() == Channel.SMS ? TestIdentityService.PHONE : TestIdentityService.EMAIL,
                c.recipient(), c.code())) return true;
        return store.verifyAndConsume(c.storageKey(), c.code());
    }
    private record EmailContent(String subject, String body) {
        static EmailContent of(String scene, String locale, String code, long seconds) {
            String l = locale == null ? "" : locale.replace('_', '-').toLowerCase(Locale.ROOT);
            boolean simplified = l.startsWith("zh-hans") || l.equals("zh-cn") || l.equals("zh-sg");
            String purpose = switch (scene) {
                case OtpScenes.LOGIN -> simplified ? "登录" : "登入";
                case OtpScenes.PASSWORD_RESET -> simplified ? "密码重置" : "密碼重設";
                case OtpScenes.PHONE_CHANGE -> simplified ? "手机号变更" : "手機號碼變更";
                case OtpScenes.EMAIL_CHANGE -> simplified ? "邮箱变更" : "電子郵件變更";
                case OtpScenes.ORDER_PICKUP_CONFIRM -> simplified ? "提货确认" : "取貨確認";
                default -> throw BizException.of(ErrorCode.BAD_REQUEST);
            };
            String validity = seconds % 60 == 0 ? (seconds / 60) + (simplified ? " 分钟" : " 分鐘") : seconds + " 秒";
            if (simplified) return new EmailContent("NearGo 商户" + purpose + "验证码",
                    "您的" + purpose + "验证码是 " + code + "，" + validity + "内有效。\n请勿将验证码提供给任何人；如非本人操作，请忽略本邮件。");
            return new EmailContent("NearGo 商戶" + purpose + "驗證碼",
                    "您的" + purpose + "驗證碼是 " + code + "，" + validity + "內有效。\n請勿將驗證碼提供給任何人；如非本人操作，請忽略本郵件。");
        }
    }
}
