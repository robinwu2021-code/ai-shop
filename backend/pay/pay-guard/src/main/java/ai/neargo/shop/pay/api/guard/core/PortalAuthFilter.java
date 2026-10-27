package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.CachedBodyRequest;
import ai.neargo.shop.pay.api.guard.core.PortalRequests;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 一个入口一条鉴权过滤器（ADR-044；安全规范 §5.1）：缓存请求体 → 交给本入口的 {@link PortalAuthenticator} →
 * 认不出就 401、<b>不进 controller</b>；认出了把调用方放进请求属性。不论成败，请求结束时写一行
 * {@code pay_api_request_log}（鉴权失败记 {@code REJECTED_AUTH}，原因只进记录不回给调用方）。
 */
public class PortalAuthFilter extends OncePerRequestFilter {

    /** 鉴权器写进请求属性的失败原因（只进记录） */
    public static final String FAILURE = PortalAuthFilter.class.getName() + ".failure";

    private final PortalFace face;
    private final PortalAuthenticator authenticator;
    private final ApiRequestRecorder recorder;

    public PortalAuthFilter(PortalFace face, PortalAuthenticator authenticator, ApiRequestRecorder recorder) {
        this.face = face;
        this.authenticator = authenticator;
        this.recorder = recorder;
    }

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
        CachedBodyRequest req;
        try {
            req = new CachedBodyRequest(raw);
        } catch (IOException tooLarge) {
            reject(raw, resp, 413, ErrorCode.BAD_REQUEST, requestId, started, "REJECTED_PARAM", tooLarge.getMessage(), null);
            return;
        }
        Optional<PortalPrincipal> who = authenticator.authenticate(req);
        if (who.isEmpty()) {
            reject(req, resp, 401, ErrorCode.UNAUTHORIZED, requestId, started, "REJECTED_AUTH",
                    String.valueOf(req.getAttribute(FAILURE)), req.body());
            return;
        }
        req.setAttribute(PortalRequests.PRINCIPAL, who.get());
        try {
            chain.doFilter(req, resp);
        } finally {
            int status = resp.getStatus();
            Object code = req.getAttribute(PortalRequests.RESULT_CODE);
            recorder.record(row(req, who.get(), requestId, started, status,
                    code == null ? (status < 400 ? "OK" : "HTTP_" + status) : code.toString(), outcomeOf(status), req.body()));
        }
    }

    private void reject(HttpServletRequest req, HttpServletResponse resp, int http, ErrorCode code, String requestId,
                        long started, String outcome, String reason, byte[] body) throws IOException {
        resp.setStatus(http);
        resp.setContentType("application/json;charset=UTF-8");
        String msg = Messages.get(code.msgKey()).replace("\\", "\\\\").replace("\"", "\\\"");
        resp.getWriter().write("{\"code\":" + code.code() + ",\"message\":\"" + msg + "\",\"data\":null,\"requestId\":\""
                + requestId + "\"}");
        PayApiRequestLog row = row(req, null, requestId, started, http, code.name(), outcome, body);
        row.setResponseMasked(reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason);
        recorder.record(row);
    }

    private PayApiRequestLog row(HttpServletRequest req, PortalPrincipal who, String requestId, long started, int status,
                                 String resultCode, String outcome, byte[] body) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        PayApiRequestLog r = new PayApiRequestLog();
        r.setTraceId(requestId);
        r.setEntry(entryOf(face));
        r.setApi(cut(req.getMethod() + " " + maskPath(face, path), 128));
        r.setPayAction(PortalActions.of(face, req.getMethod(), path));
        r.setSourceType(sourceOf(face));
        r.setAppNo(face == PortalFace.OPEN ? cut(who != null ? who.subjectId() : req.getHeader("X-App-Id"), 64) : null);
        r.setClientType(face == PortalFace.OPEN ? "SERVER" : face == PortalFace.CHECKOUT ? "H5" : "SERVER");
        r.setClientIp(ClientMeta.clientIp(req));
        r.setUserAgentDigest(digest(req.getHeader("User-Agent")));
        r.setIdempotencyKey(cut(req.getHeader(PortalRequests.IDEMPOTENCY_KEY), 128));
        r.setRequestMasked(body == null || body.length == 0 ? null
                : cut(PortalMasking.mask(new String(body, StandardCharsets.UTF_8)), 4000));
        r.setHttpStatus(status);
        r.setResultCode(cut(resultCode, 64));
        r.setOutcome(outcome);
        r.setElapsedMs((int) (System.currentTimeMillis() - started));
        r.setReceivedAt(LocalDateTime.now(ZoneOffset.UTC));
        return r;
    }

    /**
     * 收银台的令牌与码号就在路径里（安全规范 §2.5 / §4.8）：记录里只留 SHA-256 前 8 位，够对上是哪一张、拿不回原值。
     */
    static String maskPath(PortalFace face, String path) {
        if (face != PortalFace.CHECKOUT) {
            return path;
        }
        java.util.regex.Matcher m = TOKEN_SEGMENT.matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, m.group(1) + "#" + digest(m.group(2)).substring(0, 8));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final java.util.regex.Pattern TOKEN_SEGMENT =
            java.util.regex.Pattern.compile("(/(?:sessions|codes)/)([^/]+)");

    static String outcomeOf(int status) {
        if (status < 400) {
            return "ACCEPTED";
        }
        return switch (status) {
            case 401 -> "REJECTED_AUTH";
            case 403 -> "REJECTED_FORBIDDEN";
            case 409 -> "DUPLICATE";
            default -> status >= 500 ? "ERROR" : "REJECTED_PARAM";
        };
    }

    private static String entryOf(PortalFace f) {
        return switch (f) {
            case OPEN -> "OPEN_API";
            case TERMINAL -> "TERMINAL_API";
            case BIZ -> "BIZ_API";
            case OPS -> "OPS_API";
            case CHECKOUT -> "CHECKOUT";
        };
    }

    private static String sourceOf(PortalFace f) {
        return switch (f) {
            case OPEN -> "PARTNER";
            case TERMINAL -> "TERMINAL";
            case BIZ -> "BIZ";
            case OPS -> "OPS";
            case CHECKOUT -> "CHECKOUT";
        };
    }

    private static String digest(String s) {
        if (s == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
