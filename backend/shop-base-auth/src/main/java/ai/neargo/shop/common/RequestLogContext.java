package ai.neargo.shop.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.web.servlet.HandlerMapping;

/** Request attributes shared by the access logger and the existing response writers. */
public final class RequestLogContext {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID = RequestLogContext.class.getName() + ".id";
    public static final String BUSINESS_CODE = RequestLogContext.class.getName() + ".code";
    public static final String STORE_NO = RequestLogContext.class.getName() + ".storeNo";
    public static final String MERCHANT_NO = RequestLogContext.class.getName() + ".merchantNo";

    private static final List<String> PREFIXES = List.of(
            "/ops", "/biz", "/mp", "/common", "/internal", "/open", "/checkout", "/actuator");
    private static final Set<String> METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");

    private RequestLogContext() {
    }

    public static String method(HttpServletRequest request) {
        String method = request.getMethod();
        return METHODS.contains(method) ? method : "OTHER";
    }

    public static String route(HttpServletRequest request, int status) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String template && !template.isBlank() && !"/error".equals(template)) {
            return template;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = PREFIXES.stream().filter(value -> path.equals(value) || path.startsWith(value + "/"))
                .findFirst().orElse("");
        Object code = request.getAttribute(BUSINESS_CODE);
        boolean denied = status == 401 || status == 403
                || Integer.valueOf(ErrorCode.FORBIDDEN.code()).equals(code);
        return prefix + (denied ? "/SECURITY_REJECTED" : "/UNMATCHED");
    }

    public static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(REQUEST_ID);
    }
}
