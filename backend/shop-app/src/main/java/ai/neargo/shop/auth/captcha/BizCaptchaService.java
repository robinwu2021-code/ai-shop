package ai.neargo.shop.auth.captcha;

import ai.neargo.shop.common.otp.OtpScenes;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;

/** Shared server policy for the anonymous merchant authentication entry points. */
public interface BizCaptchaService {
    enum Action { LOGIN, OTP_SEND, EMAIL_OTP_SEND }
    record Challenge(String challengeId, String backgroundImage, String templateImage,
                     int backgroundImageWidth, int backgroundImageHeight,
                     int templateImageWidth, int templateImageHeight, int expiresInSeconds) {}
    record Proof(String captchaToken, int expiresInSeconds) {}
    default Challenge issue(Action action, String principal, String clientIp) {
        return issue(action, principal, clientIp, OtpScenes.LOGIN, null);
    }
    Challenge issue(Action action, String principal, String clientIp, String scene, String businessKey);
    default Proof verify(String challengeId, Action action, String principal, ImageCaptchaTrack track, String clientIp) {
        return verify(challengeId, action, principal, track, clientIp, OtpScenes.LOGIN, null);
    }
    Proof verify(String challengeId, Action action, String principal, ImageCaptchaTrack track, String clientIp, String scene, String businessKey);
    void require(Action action, String principal, String token);
}
