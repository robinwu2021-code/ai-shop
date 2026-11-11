package ai.neargo.shop.user.service;

import ai.neargo.shop.spi.platform.SmsAdmissionPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.ratelimit.InMemoryRateLimiter;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.marketing.FissionPort;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.spi.user.WxPhonePort;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.service.impl.AuthServiceImpl;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * TDD-短信按号段走模拟通道：台湾 / 阿联酋还没有国际短信通道，这两个号段的验证码只存码、打 debug 日志，
 * 不调短信通道；大陆号码照常走现有短信实现。未配置时行为与原来完全一致。
 */
class SmsMockPrefixTest {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();
    private final OtpStore store = new OtpStore(limiter);
    private final SmsPort sms = mock(SmsPort.class);

    private AuthServiceImpl auth(String mockPrefixes) {
        var auth = new AuthServiceImpl(mock(UserMapper.class), mock(IdentityMapper.class),
                mock(WxPhonePort.class), mock(MpSessionService.class),
                new MemoryTokenStore(Duration.ofHours(1)), store, mock(OtpSendGuard.class), sms,
                mock(WxAuthPort.class), mock(PasswordHasher.class), limiter, mock(PersonService.class), "",
                mock(FissionPort.class), mock(OtpTestPhoneService.class), mock(EmailOtpService.class), mock(SmsAdmissionPort.class));
        auth.setMockSmsPrefixes(mockPrefixes);
        return auth;
    }

    @Test
    void taiwanAndUaeNumbersGetAStoredCodeButNoSms() {
        var auth = auth("+886, +971");
        auth.sendOtp("+886912345678");
        auth.sendOtp("+971501234567");
        assertThat(store.peek("+886912345678")).get().asString().matches("[0-9]{6}");
        assertThat(store.peek("+971501234567")).get().asString().matches("[0-9]{6}");
        verify(sms, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void mainlandNumbersStillGoThroughTheRealSmsPort() {
        var auth = auth("+886,+971");
        auth.sendOtp("13800138000");
        String code = store.peek("13800138000").orElseThrow();
        verify(sms).sendOtp(eq("13800138000"), eq(code));
    }

    @Test
    void withoutConfigurationEveryNumberUsesTheSmsPortAsBefore() {
        var auth = auth("");
        auth.sendOtp("+886912345678");
        verify(sms).sendOtp(eq("+886912345678"), anyString());
    }
}
