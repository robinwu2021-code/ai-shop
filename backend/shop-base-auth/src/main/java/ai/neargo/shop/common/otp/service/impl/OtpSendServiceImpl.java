package ai.neargo.shop.common.otp.service.impl;

import ai.neargo.shop.auth.captcha.CaptchaProofService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.otp.OtpKeys;
import ai.neargo.shop.common.otp.OtpScenePolicy.CaptchaMode;
import ai.neargo.shop.common.otp.policy.InMemoryOtpFrequency;
import ai.neargo.shop.common.otp.policy.OtpScenePolicies;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.ratelimit.OtpSendGuard;
import ai.neargo.shop.spi.otp.OtpChannelPort;
import ai.neargo.shop.spi.otp.OtpChannelPort.DeliveryCommand;
import ai.neargo.shop.spi.otp.OtpChannelPort.VerificationCommand;
import java.util.Map;
import org.springframework.validation.annotation.Validated;

@Validated
public class OtpSendServiceImpl implements OtpSendService {
    private final OtpScenePolicies policies;
    private final InMemoryOtpFrequency frequency;
    private final CaptchaProofService proofs;
    private final OtpSendGuard guard;
    private final OtpChannelPort channel;
    public OtpSendServiceImpl(OtpScenePolicies policies, InMemoryOtpFrequency frequency,
                             CaptchaProofService proofs, OtpSendGuard guard, OtpChannelPort channel) {
        this.policies = policies; this.frequency = frequency; this.proofs = proofs;
        this.guard = guard; this.channel = channel;
    }
    @Override public OtpSendResult send(SendCommand command, OtpContext context) {
        var rule = policies.require(command.scene(), command.channel(), context.entryCode(), command.businessKey());
        // No caller-controlled message/OTP/template parameters are currently registered.
        if (command.parameters() != null && !command.parameters().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        String recipient = OtpKeys.normalize(command.channel(), command.recipient());
        channel.requireAvailable(command.scene(), command.channel(), recipient);
        String account = OtpKeys.recipientKey(command.channel(), recipient);
        var mode = policies.mode(rule, context.entryCode());
        if (mode == CaptchaMode.ALWAYS || (mode == CaptchaMode.ADAPTIVE && frequency.requiresCaptcha(account, rule)))
            proofs.require(OtpKeys.action(command.channel()), recipient, command.scene(), command.businessKey(), command.captchaToken());
        guard.check(account, context.senderKey(), context.clientIp());
        frequency.record(account);
        var delivered = channel.deliver(new DeliveryCommand(command.scene(), command.channel(), recipient,
                OtpKeys.storageKey(command.scene(), command.channel(), recipient, command.businessKey()),
                rule.validity().get(command.channel()), command.locale(), "OTP_" + command.scene(), Map.of()));
        return new OtpSendResult(delivered.expiresInSeconds(), guard.resendAfterSeconds());
    }
    @Override public boolean verifyAndConsume(VerifyCommand command, OtpContext context) {
        policies.require(command.scene(), command.channel(), context.entryCode(), command.businessKey());
        String recipient = OtpKeys.normalize(command.channel(), command.recipient());
        return channel.verifyAndConsume(new VerificationCommand(command.scene(), command.channel(), recipient,
                OtpKeys.storageKey(command.scene(), command.channel(), recipient, command.businessKey()), command.code()));
    }
}
