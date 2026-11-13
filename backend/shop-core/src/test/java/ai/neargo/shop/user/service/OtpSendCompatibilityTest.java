package ai.neargo.shop.user.service;

import ai.neargo.shop.auth.captcha.CaptchaProofService;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.otp.*;
import ai.neargo.shop.common.otp.policy.*;
import ai.neargo.shop.common.otp.service.OtpSendService.*;
import ai.neargo.shop.common.otp.service.impl.OtpSendServiceImpl;
import ai.neargo.shop.common.ratelimit.*;
import ai.neargo.shop.spi.notify.*;
import ai.neargo.shop.spi.otp.OtpChannelPort.*;
import ai.neargo.shop.spi.platform.SmsAdmissionPort;
import ai.neargo.shop.user.port.OtpChannelPortImpl;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OtpSendCompatibilityTest {
    final OtpStore store = new OtpStore(new InMemoryRateLimiter());
    final SmsPort sms = mock(SmsPort.class);
    final MailPort mail = mock(MailPort.class);
    final TestIdentityService identities = mock(TestIdentityService.class);
    final OtpChannelPortImpl channel = new OtpChannelPortImpl(store,sms,mail,mock(SmsAdmissionPort.class),
            mock(OtpTestPhoneService.class),identities,"",false,false,"");
    DeliveryCommand delivery(String scene,Channel kind,String recipient,String business) {
        return new DeliveryCommand(scene,kind,recipient,OtpKeys.storageKey(scene,kind,recipient,business),
                Duration.ofMinutes(5),"zh-Hant","OTP_"+scene,java.util.Map.of());
    }
    @Test void smsIsActiveBeforeTransportWhileEmailIsActiveOnlyAfterSuccess() {
        var smsCommand = delivery(OtpScenes.LOGIN,Channel.SMS,"13800000000",null);
        doAnswer(i -> { assertThat(store.peek(smsCommand.storageKey())).contains(i.getArgument(1)); throw new SmsPort.SmsException("failed",false); })
                .when(sms).sendOtp(anyString(),anyString());
        assertThatThrownBy(() -> channel.deliver(smsCommand)).isInstanceOf(SmsPort.SmsException.class);
        assertThat(store.peek(smsCommand.storageKey())).isPresent();
        var emailCommand = delivery(OtpScenes.LOGIN,Channel.EMAIL,"a@example.test",null);
        doAnswer(i -> { assertThat(store.peek(emailCommand.storageKey())).isEmpty(); throw new MailPort.MailException("private SMTP details"); })
                .when(mail).send(anyString(),anyString(),anyString(),anyString(),isNull());
        assertThatThrownBy(() -> channel.deliver(emailCommand)).hasMessageNotContaining("private SMTP");
        assertThat(store.peek(emailCommand.storageKey())).isEmpty();
    }
    @Test void loginTestIdentityRemainsSyntheticButOtherScenesDoNotInheritBypass() {
        when(identities.isTestIdentity(TestIdentityService.EMAIL,"test@example.test")).thenReturn(true);
        when(identities.acceptsCode(TestIdentityService.EMAIL,"test@example.test","123456")).thenReturn(true);
        var login = delivery(OtpScenes.LOGIN,Channel.EMAIL,"test@example.test",null);
        channel.deliver(login);
        assertThat(store.peek(login.storageKey())).isEmpty();
        assertThat(channel.verifyAndConsume(new VerificationCommand(login.scene(),login.channel(),login.recipient(),login.storageKey(),"123456"))).isTrue();
        verifyNoInteractions(mail);
        var reset = delivery(OtpScenes.PASSWORD_RESET,Channel.EMAIL,"test@example.test",null);
        channel.deliver(reset);
        verify(mail).send(eq("test@example.test"),contains("密碼重設"),contains("5 分鐘"),eq(NotifyBizType.OTP),isNull());
        assertThat(store.peek(reset.storageKey())).isPresent();
        assertThat(channel.verifyAndConsume(new VerificationCommand(reset.scene(),reset.channel(),reset.recipient(),reset.storageKey(),"123456"))).isFalse();
    }
    @Test void subMinuteAndFractionalMinuteEmailValidityMatchesConfiguredSeconds() {
        for (int seconds : new int[]{30,90}) {
            String email = "ttl"+seconds+"@example.test";
            var c = delivery(OtpScenes.PASSWORD_RESET,Channel.EMAIL,email,null);
            channel.deliver(new DeliveryCommand(c.scene(),c.channel(),c.recipient(),c.storageKey(),Duration.ofSeconds(seconds),
                    c.locale(),c.logicalTemplateKey(),c.parameters()));
            verify(mail).send(eq(email),anyString(),contains(seconds+" 秒內有效"),eq(NotifyBizType.OTP),isNull());
        }
    }
    @Test void enabledSceneTtlAndBusinessIsolationUseSamePublicService() {
        var settings = new OtpSettings();
        var pickup = new OtpSettings.Scene(); pickup.setEnabled(true); pickup.setEntries(Set.of("ORDER")); pickup.setSmsTtlSeconds(120);
        settings.getScenes().put(OtpScenes.ORDER_PICKUP_CONFIRM,pickup);
        var guard = mock(OtpSendGuard.class);
        var service = new OtpSendServiceImpl(new OtpScenePolicies(settings,false),
                new InMemoryOtpFrequency(Clock.systemUTC(),100,500,3000),mock(CaptchaProofService.class),guard,channel);
        var context = new OtpContext("ORDER","ip",null);
        var sent = service.send(new SendCommand(OtpScenes.ORDER_PICKUP_CONFIRM,Channel.SMS,"13800000000","order1",null,null,null),context);
        assertThat(sent.expiresInSeconds()).isEqualTo(120);
        String key = OtpKeys.storageKey(OtpScenes.ORDER_PICKUP_CONFIRM,Channel.SMS,"13800000000","order1");
        String code = store.peek(key).orElseThrow();
        assertThat(service.verifyAndConsume(new VerifyCommand(OtpScenes.ORDER_PICKUP_CONFIRM,Channel.SMS,"13800000000","order2",code),context)).isFalse();
        assertThat(service.verifyAndConsume(new VerifyCommand(OtpScenes.ORDER_PICKUP_CONFIRM,Channel.SMS,"13800000000","order1",code),context)).isTrue();
        assertThat(service.verifyAndConsume(new VerifyCommand(OtpScenes.ORDER_PICKUP_CONFIRM,Channel.SMS,"13800000000","order1",code),context)).isFalse();
        verify(sms).sendOtpForScene("13800000000",code,OtpScenes.ORDER_PICKUP_CONFIRM,NotifyBizType.OTP,null);
    }
}
