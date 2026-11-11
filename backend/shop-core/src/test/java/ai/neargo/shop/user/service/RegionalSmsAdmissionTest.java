package ai.neargo.shop.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.ratelimit.InMemoryRateLimiter;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.platform.SmsAdmissionPort;
import ai.neargo.shop.spi.marketing.FissionPort;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.spi.user.WxPhonePort;
import ai.neargo.shop.user.mapper.UserMappers.*;
import ai.neargo.shop.user.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;

class RegionalSmsAdmissionTest {
    @Test void disabledRegionCannotSendOrVerifyEvenWithFixedCodesMocksAndTestWhitelist() {
        var policy=mock(SmsAdmissionPort.class);var otp=mock(OtpStore.class);var sms=mock(SmsPort.class);var guard=mock(OtpSendGuard.class);
        doThrow(BizException.of(ErrorCode.SMS_VERIFICATION_DISABLED)).when(policy).requireSmsEnabled(anyString());
        var auth=new AuthServiceImpl(mock(UserMapper.class),mock(IdentityMapper.class),mock(WxPhonePort.class),mock(MpSessionService.class),
                mock(TokenStore.class),otp,guard,sms,mock(WxAuthPort.class),mock(PasswordHasher.class),new InMemoryRateLimiter(),
                mock(PersonService.class),"123456",mock(FissionPort.class),mock(OtpTestPhoneService.class),mock(EmailOtpService.class),policy);
        auth.setMockSmsPrefixes("+886");var identities=mock(TestIdentityService.class);
        when(identities.acceptsCode(TestIdentityService.PHONE,"+886900001111","123456")).thenReturn(true);auth.setTestIdentities(identities);
        assertEquals(ErrorCode.SMS_VERIFICATION_DISABLED,assertThrows(BizException.class,()->auth.sendOtp("+886900001111")).errorCode());
        assertEquals(ErrorCode.SMS_VERIFICATION_DISABLED,assertThrows(BizException.class,()->auth.login(new AuthService.LoginCommand(AuthService.GRANT_PHONE_OTP,"+886900001111","123456",null,null,true))).errorCode());
        verifyNoInteractions(otp,sms,guard,identities);
    }
}
