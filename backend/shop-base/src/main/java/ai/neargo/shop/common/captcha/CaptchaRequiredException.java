package ai.neargo.shop.common.captcha;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** A server policy decision, including the exact action that needs fresh proof. */
public class CaptchaRequiredException extends BizException {
    private final String action;
    public CaptchaRequiredException(String action) { super(ErrorCode.CAPTCHA_INVALID); this.action = action; }
    public String action() { return action; }
}
