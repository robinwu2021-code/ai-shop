package ai.neargo.shop.user.service;

import ai.neargo.shop.auth.captcha.CaptchaProofService;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.otp.policy.InMemoryOtpFrequency;
import ai.neargo.shop.common.otp.policy.OtpScenePolicies;
import ai.neargo.shop.common.otp.policy.OtpSettings;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.otp.service.impl.OtpSendServiceImpl;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.notify.MailPort;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.platform.SmsAdmissionPort;
import ai.neargo.shop.user.port.OtpChannelPortImpl;
import java.time.Clock;
import static org.mockito.Mockito.*;

public final class OtpFixtures {
    private OtpFixtures() {}
    public static OtpSendService create(OtpStore store, OtpSendGuard guard, SmsPort sms, MailPort mail,
            SmsAdmissionPort admission, OtpTestPhoneService phones, TestIdentityService identities,
            String fixed, boolean mailStub, boolean allowedStub, String prefixes) {
        if (mockingDetails(guard).isMock()) when(guard.resendAfterSeconds()).thenReturn(60);
        var channel = new OtpChannelPortImpl(store, sms, mail, admission, phones, identities, fixed, mailStub, allowedStub, prefixes);
        return new OtpSendServiceImpl(new OtpScenePolicies(new OtpSettings(), false),
                new InMemoryOtpFrequency(Clock.systemUTC(), 100000, 500, 3000), mock(CaptchaProofService.class), guard, channel);
    }
    public static OtpSendService email(OtpStore store, OtpSendGuard guard, MailPort mail, boolean stub, boolean allowed) {
        return create(store, guard, mock(SmsPort.class), mail, mock(SmsAdmissionPort.class),
                mock(OtpTestPhoneService.class), mock(TestIdentityService.class), "", stub, allowed, "");
    }
}
