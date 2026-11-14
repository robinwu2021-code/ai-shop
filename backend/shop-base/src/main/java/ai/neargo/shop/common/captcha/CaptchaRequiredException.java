package ai.neargo.shop.common.captcha;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;

public class CaptchaRequiredException extends BizException {
    private final String action, scene, businessKey;
    public CaptchaRequiredException(String action) { this(action, null, null); }
    public CaptchaRequiredException(String action, String scene, String businessKey) {
        super(ErrorCode.CAPTCHA_INVALID); this.action = action; this.scene = scene; this.businessKey = businessKey;
    }
    public String action() { return action; }
    public Map<String, Object> details() {
        var result = new LinkedHashMap<String, Object>();
        result.put("captchaRequired", true); result.put("action", action);
        if (scene != null) result.put("scene", scene);
        if (businessKey != null && !businessKey.isBlank()) result.put("businessKey", businessKey);
        return Map.copyOf(result);
    }
}
