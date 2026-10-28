package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.api.guard.PortalFace;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 统一管线（ADR-045；TDD-支付服务-接入守卫模块pay-guard §2.2）：一个入口一条，入口的差异全在 {@link EntryGuard}。
 *
 * <pre>
 * ① 只管本入口前缀        ② 缓存请求体（超 1MB → 413）
 * ③–⑧ EntryGuard#authenticate：基础校验、认人、验证、时间窗与 nonce、IP、授权范围（按入口开关）
 *      认不出 → 401（或入口给的状态），统一错误体，<b>不进 controller</b>
 * ⑨ EntryGuard#decryptSensitiveFields      ⑩ 认出的调用方放进请求属性
 * → controller
 * ⑪ EntryGuard#signResponse（只在入口要签时缓存响应体）
 * ⑫ 写 pay_api_request_log —— 不论成败
 * </pre>
 */
public class GuardFilter extends OncePerRequestFilter {

    private final PortalFace face;
    private final EntryGuard guard;
    private final RequestLogWriter log;

    public GuardFilter(PortalFace face, EntryGuard guard, RequestLogWriter log) {
        this.face = face;
        this.guard = guard;
        this.log = log;
    }

    /** ① 只管本入口前缀 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !face.owns(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest raw, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        long started = System.currentTimeMillis();
        String requestId = PortalRequests.requestId(raw);
        resp.setHeader("X-Request-Id", requestId);

        // ② 缓存请求体：验签要原始字节，controller 还要再读一次
        CachedBodyRequest req;
        try {
            req = new CachedBodyRequest(raw);
        } catch (IOException tooLarge) {
            GuardErrors.write(resp, 413, ErrorCode.BAD_REQUEST, requestId);
            log.write(face, raw, null, requestId, started, 413, ErrorCode.BAD_REQUEST.name(), "REJECTED_PARAM", null,
                    tooLarge.getMessage());
            return;
        }

        // ③–⑧ 认人（按入口）
        GuardResult result = guard.authenticate(req);
        if (!result.ok()) {
            GuardErrors.write(resp, result.status(), result.code(), requestId);
            log.write(face, req, null, requestId, started, result.status(), result.code().name(), result.outcome(),
                    req.body(), String.valueOf(result.reason()));
            return;
        }
        PortalPrincipal who = result.principal();

        // ⑨ 敏感字段解密
        guard.decryptSensitiveFields(req, who);
        // ⑩ 认出的调用方放进请求属性（controller 经参数拿到它）
        req.setAttribute(PortalRequests.PRINCIPAL, who);

        // ⑪ 要签响应的入口才缓存响应体；不签的入口响应原样流出
        ContentCachingResponseWrapper signing = guard.signsResponses() ? new ContentCachingResponseWrapper(resp) : null;
        try {
            chain.doFilter(req, signing != null ? signing : resp);
            if (signing != null) {
                guard.signResponse(signing, signing.getContentAsByteArray(), who);
                signing.copyBodyToResponse();
            }
        } finally {
            // ⑫ 留痕
            int status = resp.getStatus();
            Object code = req.getAttribute(PortalRequests.RESULT_CODE);
            log.write(face, req, who, requestId, started, status,
                    code == null ? (status < 400 ? "OK" : "HTTP_" + status) : code.toString(),
                    RequestLogWriter.outcomeOf(status), req.body(), null);
        }
    }
}
