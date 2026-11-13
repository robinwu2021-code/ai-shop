package ai.neargo.shop.auth.captcha;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;

/** Shared server policy for the anonymous merchant authentication entry points. */
public interface BizCaptchaService {
    enum Action { LOGIN, OTP_SEND, EMAIL_OTP_SEND }
    record Challenge(String challengeId, String backgroundImage, String templateImage,
                     int backgroundImageWidth, int backgroundImageHeight,
                     int templateImageWidth, int templateImageHeight, int expiresInSeconds) {}
    record Proof(String captchaToken, int expiresInSeconds) {}
    Challenge issue(Action action, String principal, String clientIp);
    Proof verify(String challengeId, Action action, String principal, ImageCaptchaTrack track, String clientIp);
    void require(Action action, String principal, String token);
}
