package ai.neargo.shop.common.otp;

import ai.neargo.shop.auth.captcha.CaptchaProofService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.captcha.CaptchaRequiredException;
import ai.neargo.shop.common.otp.policy.*;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.otp.service.OtpSendService.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import ai.neargo.shop.common.otp.service.impl.OtpSendServiceImpl;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.otp.OtpChannelPort;
import jakarta.validation.Validation;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OtpSendServiceTest {
    final CaptchaProofService proofs = mock(CaptchaProofService.class);
    final OtpSendGuard guard = mock(OtpSendGuard.class);
    final OtpChannelPort channel = mock(OtpChannelPort.class);
    final InMemoryOtpFrequency frequency = new InMemoryOtpFrequency(Clock.systemUTC(),100,500,3000);
    final OtpScenePolicies policies = new OtpScenePolicies(new OtpSettings(),true);
    final OtpSendService service = validatedService();
    OtpSendService validatedService() {
        var proxy = new ProxyFactory(new OtpSendServiceImpl(policies,frequency,proofs,guard,channel));
        proxy.addAdvice(new MethodValidationInterceptor(Validation.buildDefaultValidatorFactory().getValidator()));
        return (OtpSendService) proxy.getProxy();
    }
    final OtpContext context = new OtpContext(OtpEntries.BIZ_LOGIN,"1.2.3.4","device");
    SendCommand command(String token) { return new SendCommand(OtpScenes.LOGIN,Channel.EMAIL,"a@example.test",null,"zh-Hant",Map.of(),token); }
    void deliver() { when(channel.deliver(any())).thenReturn(new OtpChannelPort.DeliveryResult(600)); when(guard.resendAfterSeconds()).thenReturn(60); }
    void prime() { for(int i=0;i<3;i++) frequency.record(OtpKeys.recipientKey(Channel.EMAIL,"a@example.test")); }
    @Test void lowFrequencySendsSkipProofAndPassTrustedContextAndTtl() {
        deliver();
        for(int i=0;i<3;i++) assertThat(service.send(command(null),context)).isEqualTo(new OtpSendResult(600,60));
        verifyNoInteractions(proofs);
        verify(guard,times(3)).check(OtpKeys.recipientKey(Channel.EMAIL,"a@example.test"),"device","1.2.3.4");
        var capture = org.mockito.ArgumentCaptor.forClass(OtpChannelPort.DeliveryCommand.class);
        verify(channel,times(3)).deliver(capture.capture());
        assertThat(capture.getValue().storageKey()).isEqualTo(OtpKeys.recipientKey(Channel.EMAIL,"a@example.test"));
        assertThat(capture.getValue().ttl().toSeconds()).isEqualTo(600);
    }
    @Test void missingProofHasNoHardGuardOrSendSideEffects() {
        prime();
        doThrow(new CaptchaRequiredException("EMAIL_OTP_SEND",OtpScenes.LOGIN,null)).when(proofs)
                .require("EMAIL_OTP_SEND","a@example.test",OtpScenes.LOGIN,null,null);
        assertThatThrownBy(() -> service.send(command(null),context)).isInstanceOf(CaptchaRequiredException.class);
        verifyNoInteractions(guard); verify(channel,never()).deliver(any());
        verify(proofs).require("EMAIL_OTP_SEND","a@example.test",OtpScenes.LOGIN,null,null);
    }
    @Test void proofIsConsumedBeforeHardGuardAndAttemptCountsBeforeDeliveryFailure() {
        prime();
        doThrow(BizException.of(ErrorCode.BAD_REQUEST)).when(guard).check(anyString(),any(),any());
        assertThatThrownBy(() -> service.send(command("token"),context)).isInstanceOf(BizException.class);
        var order = inOrder(proofs,guard,channel);
        order.verify(channel).requireAvailable(OtpScenes.LOGIN,Channel.EMAIL,"a@example.test");
        order.verify(proofs).require("EMAIL_OTP_SEND","a@example.test",OtpScenes.LOGIN,null,"token");
        order.verify(guard).check(anyString(),any(),any());
        verify(channel,never()).deliver(any());
        reset(guard,proofs,channel); deliver();
        when(channel.deliver(any())).thenThrow(BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
        assertThatThrownBy(() -> service.send(command("token2"),context)).isInstanceOf(BizException.class);
        verify(proofs).require("EMAIL_OTP_SEND","a@example.test",OtpScenes.LOGIN,null,"token2");
    }
    @Test void legacyEntryNeverRequiresSliderButSharesAccountCounter() {
        deliver(); prime();
        service.send(command(null),new OtpContext(OtpEntries.LEGACY_AUTH,"ip",null));
        verifyNoInteractions(proofs);
        assertThat(frequency.requiresCaptcha(OtpKeys.recipientKey(Channel.EMAIL,"a@example.test"),
                policies.require(OtpScenes.LOGIN,Channel.EMAIL,OtpEntries.BIZ_LOGIN,null))).isTrue();
    }
    @Test void invalidRecipientAndArbitraryTemplateParametersCannotReachChannel() {
        assertThatThrownBy(() -> service.send(new SendCommand(OtpScenes.LOGIN,Channel.EMAIL,"invalid",null,null,null,null),context))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> service.send(new SendCommand(OtpScenes.LOGIN,Channel.EMAIL,"a@example.test",null,null,Map.of("code","123456"),null),context))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(channel,guard,proofs);
    }
    @Test void verifyUsesTheSameCanonicalKeyWithoutSendGuards() {
        when(channel.verifyAndConsume(any())).thenReturn(true);
        assertThat(service.verifyAndConsume(new VerifyCommand(OtpScenes.LOGIN,Channel.EMAIL,"A@example.test",null,"123456"),context)).isTrue();
        verify(channel).verifyAndConsume(new OtpChannelPort.VerificationCommand(OtpScenes.LOGIN,Channel.EMAIL,"a@example.test",
                OtpKeys.recipientKey(Channel.EMAIL,"a@example.test"),"123456"));
        verifyNoInteractions(guard,proofs);
    }
}
