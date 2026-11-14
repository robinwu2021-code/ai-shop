package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.otp.OtpEntries;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import ai.neargo.shop.common.otp.service.OtpSendService.OtpContext;
import ai.neargo.shop.common.otp.service.OtpSendService.SendCommand;
import ai.neargo.shop.common.otp.service.OtpSendService.VerifyCommand;
import ai.neargo.shop.user.service.EmailOtpService;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Compatibility boundary; shared OTP lifecycle has one implementation. */
@Service
public class EmailOtpServiceImpl implements EmailOtpService {
    private final OtpSendService otp;
    public EmailOtpServiceImpl(OtpSendService otp) { this.otp = otp; }
    private OtpContext context() {
        var meta = RequestMetaContext.current();
        return new OtpContext(OtpEntries.LEGACY_AUTH, meta == null ? null : meta.ip(), null);
    }
    @Override public Delivery send(String email, String locale) {
        var result = otp.send(new SendCommand(OtpScenes.LOGIN, Channel.EMAIL, email, null, locale, Map.of(), null), context());
        return new Delivery(result.expiresInSeconds(), result.resendAfterSeconds());
    }
    @Override public String verify(String email, String code) {
        String normalized = EmailOtpService.normalize(email);
        if (!otp.verifyAndConsume(new VerifyCommand(OtpScenes.LOGIN, Channel.EMAIL, normalized, null, code), context()))
            throw BizException.of(ErrorCode.OTP_INVALID);
        return normalized;
    }
}
