package ai.neargo.shop.auth.captcha;

import ai.neargo.shop.common.captcha.CaptchaRequiredException;
import ai.neargo.shop.common.otp.OtpKeys;

/** Proof consumption has no image-engine or business-domain dependency. */
public class CaptchaProofService {
    private final JdbcCaptchaStore store;
    public CaptchaProofService(JdbcCaptchaStore store) { this.store = store; }
    public void require(String action, String recipient, String scene, String businessKey, String token) {
        if (store.consume(token, OtpKeys.captchaScope("proof", action, recipient, scene, businessKey)) == null)
            throw new CaptchaRequiredException(action, scene, businessKey);
    }
}
