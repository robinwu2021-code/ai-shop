package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** 接入层在请求属性里放的东西：请求号、认出的调用方、给请求记录用的结果码。只有过滤器与异常处理写它们 */
public final class PortalRequests {

    public static final String REQUEST_ID = PortalRequests.class.getName() + ".requestId";
    public static final String PRINCIPAL = PortalRequests.class.getName() + ".principal";
    public static final String RESULT_CODE = PortalRequests.class.getName() + ".resultCode";
    /** 开放 API 验签命中的密钥版本（Integer；部署配置里的密钥为空） */
    public static final String SIGN_KEY_VERSION = PortalRequests.class.getName() + ".signKeyVersion";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private PortalRequests() {
    }

    /** 入站带 {@code X-Request-Id} 就沿用（贯通 nginx 与上游），否则生成 */
    public static String requestId(HttpServletRequest req) {
        Object id = req.getAttribute(REQUEST_ID);
        if (id instanceof String s) {
            return s;
        }
        String in = req.getHeader("X-Request-Id");
        String rid = in != null && in.matches("[A-Za-z0-9._-]{8,64}") ? in : UUID.randomUUID().toString().replace("-", "");
        req.setAttribute(REQUEST_ID, rid);
        return rid;
    }

    public static PortalPrincipal principal(HttpServletRequest req) {
        return (PortalPrincipal) req.getAttribute(PRINCIPAL);
    }
}
