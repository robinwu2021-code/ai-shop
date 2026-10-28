package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.access.service.ApiRequestRecorder;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.internal.InternalGuard;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 管线 ⑫：把一次请求写成一行 {@code pay_api_request_log}（四向记录之①）。成败都写；回绝时失败原因写进 {@code response_masked}，
 * 不回给调用方。列口径只在这里定义一次 —— 以后内部调用的记录（开发计划 M2）也经这里写，两种入口的同一列不会各算各的。
 */
@Component
public class RequestLogWriter {

    private final ApiRequestRecorder recorder;

    public RequestLogWriter(ApiRequestRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * @param who    认出的调用方；回绝时为空
     * @param reason 回绝原因（只进记录，截到 500 字）；成功时为空
     */
    public void write(PortalFace face, HttpServletRequest req, PortalPrincipal who, String requestId, long startedMillis,
                      int status, String resultCode, String outcome, byte[] body, String reason) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        PayApiRequestLog r = new PayApiRequestLog();
        r.setTraceId(requestId);
        r.setEntry(entryOf(face));
        r.setApi(cut(req.getMethod() + " " + maskPath(face, path), 128));
        r.setPayAction(PortalActions.of(face, req.getMethod(), path));
        r.setSourceType(sourceOf(face));
        r.setAppNo(face == PortalFace.OPEN ? cut(who != null ? who.subjectId() : req.getHeader("X-App-Id"), 64)
                : face == PortalFace.INTERNAL && who != null ? cut(who.subjectId(), 64) : null);
        r.setClientType(face == PortalFace.OPEN ? "SERVER" : face == PortalFace.CHECKOUT ? "H5" : "SERVER");
        r.setClientIp(ClientMeta.clientIp(req));
        r.setUserAgentDigest(digest(req.getHeader("User-Agent")));
        r.setIdempotencyKey(cut(req.getHeader(PortalRequests.IDEMPOTENCY_KEY), 128));
        r.setRequestMasked(body == null || body.length == 0 ? null
                : cut(PortalMasking.mask(new String(body, StandardCharsets.UTF_8)), 4000));
        r.setResponseMasked(reason == null ? null : cut(reason, 500));
        r.setHttpStatus(status);
        r.setResultCode(cut(resultCode, 64));
        r.setOutcome(outcome);
        r.setElapsedMs((int) (System.currentTimeMillis() - startedMillis));
        r.setReceivedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (req.getAttribute(InternalGuard.SOURCE_ATTRIBUTE) instanceof CallSource source) {
            applySource(r, source);
        }
        recorder.record(r);
    }

    /**
     * 内部调用的来源落到记录上（V13 的 8 列 + 操作人；ADR-045 G9）。追踪号沿用来源的 —— 一个号串起调用方与支付两边；
     * 操作人只在记录还没有时取来源的（退款命令里的商家操作员优先）。
     */
    public static void applySource(PayApiRequestLog r, CallSource s) {
        if (s.traceId() != null && !s.traceId().isBlank()) {
            r.setTraceId(cut(s.traceId(), 64));
        }
        r.setSourceService(cut(s.service() == null ? CallSources.UNKNOWN : s.service(), 64));
        r.setSourceModule(cut(s.module(), 24));
        r.setSourceInstance(cut(s.instance(), 128));
        r.setSourceIp(cut(s.serverIp(), 64));
        r.setOriginApi(cut(s.originApi(), 128));
        r.setOriginClient(cut(s.originClient(), 24));
        r.setOriginIp(cut(s.originIp(), 64));
        r.setOriginUaDigest(cut(s.originUaDigest(), 64));
        if (r.getOperatorNo() == null) {
            r.setOperatorNo(cut(s.operatorNo(), 64));
        }
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
            // pay_api_request_log.entry 的值域（V5）：经 HTTP 的内部调用记 INTERNAL_HTTP，进程内的记 INTERNAL_PORT
            case INTERNAL -> "INTERNAL_HTTP";
            case OPEN -> "OPEN_API";
            case TERMINAL -> "TERMINAL_API";
            case BIZ -> "BIZ_API";
            case OPS -> "OPS_API";
            case CHECKOUT -> "CHECKOUT";
        };
    }

    private static String sourceOf(PortalFace f) {
        return switch (f) {
            case INTERNAL -> "INTERNAL";
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
