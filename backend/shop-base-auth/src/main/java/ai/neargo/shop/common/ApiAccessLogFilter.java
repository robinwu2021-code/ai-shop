package ai.neargo.shop.common;

import ai.neargo.job.api.JobHttpPaths;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import ai.neargo.shop.svc.InternalHttp;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Emits one timing record per request, plus a copy for slow-log queries. */
public class ApiAccessLogFilter extends OncePerRequestFilter {

    private static final Logger ACCESS = LoggerFactory.getLogger("api.access");
    private static final Logger SLOW = LoggerFactory.getLogger("api.slow");
    private static final Pattern BUSINESS_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final boolean accessLog;
    private final long slowMs;
    private final List<String> exclude;
    private final byte[] internalToken;
    private final byte[] jobToken;

    public ApiAccessLogFilter(boolean accessLog, long slowMs, List<String> exclude) {
        this(accessLog, slowMs, exclude, "", "");
    }

    public ApiAccessLogFilter(boolean accessLog, long slowMs, List<String> exclude,
                              String internalToken, String jobToken) {
        this.accessLog = accessLog;
        this.slowMs = slowMs;
        this.exclude = exclude == null ? List.of() : exclude;
        this.internalToken = tokenBytes(internalToken);
        this.jobToken = tokenBytes(jobToken);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return exclude.stream().anyMatch(pattern -> matcher.match(pattern, path));
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request) && RequestLogContext.requestId(request) != null) {
            filterWithRequestId(request, response, chain, RequestLogContext.requestId(request));
            return;
        }

        long start = System.nanoTime();
        String requestId = resolveRequestId(request);
        request.setAttribute(RequestLogContext.REQUEST_ID, requestId);
        response.setHeader(RequestLogContext.REQUEST_ID_HEADER, requestId);

        try {
            filterWithRequestId(request, response, chain, requestId);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            int status = response.isCommitted() ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            write(request, status, start, requestId);
            throw failure;
        }

        if (!request.isAsyncStarted()) {
            write(request, response.getStatus(), start, requestId);
            return;
        }
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onStartAsync(AsyncEvent event) {
                    event.getAsyncContext().addListener(this);
                }

                @Override
                public void onComplete(AsyncEvent event) {
                    write(request, response.getStatus(), start, requestId);
                }

                @Override public void onTimeout(AsyncEvent event) { }
                @Override public void onError(AsyncEvent event) { }
            });
        } catch (IllegalStateException alreadyCompleted) {
            write(request, response.getStatus(), start, requestId);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String inherited = null;
        if (path.startsWith("/internal/job/")) {
            if (matches(jobToken, request.getHeader(JobHttpPaths.TOKEN_HEADER))) {
                inherited = request.getHeader(RequestLogContext.REQUEST_ID_HEADER);
            }
        } else if (path.startsWith("/internal/")
                && matches(internalToken, request.getHeader(InternalHttp.TOKEN_HEADER))) {
            inherited = CallSourceCodec.decode(request.getHeader(InternalHeaders.CALL_SOURCE))
                    .map(source -> source.traceId()).orElse(null);
        }
        return CallSources.isTraceId(inherited)
                ? inherited : UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] tokenBytes(String token) {
        return token == null || token.isBlank() ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean matches(byte[] expected, String supplied) {
        return expected.length > 0 && supplied != null
                && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static void filterWithRequestId(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain, String requestId) throws ServletException, IOException {
        String previousId = MDC.get("requestId");
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previousId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousId);
            }
        }
    }

    private void write(HttpServletRequest request, int status, long start, String requestId) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (!accessLog && elapsed < slowMs) {
            return;
        }

        Object code = request.getAttribute(RequestLogContext.BUSINESS_CODE);
        String outcome;
        if (status >= 500 || Integer.valueOf(ErrorCode.INTERNAL_ERROR.code()).equals(code)) {
            outcome = "SYSTEM_ERROR";
        } else if (status >= 400 || code instanceof Integer businessCode && businessCode != 0) {
            outcome = "REJECTED";
        } else {
            outcome = "OK";
        }

        var line = new StringBuilder(180)
                .append("api m=").append(RequestLogContext.method(request))
                .append(" p=").append(RequestLogContext.route(request, status))
                .append(" s=").append(status)
                .append(" ms=").append(elapsed)
                .append(" rid=").append(requestId);
        if (code instanceof Integer businessCode) {
            line.append(" bizCode=").append(businessCode);
        }
        line.append(" outcome=").append(outcome);
        appendId(line, " store=", request.getAttribute(RequestLogContext.STORE_NO));
        appendId(line, " mch=", request.getAttribute(RequestLogContext.MERCHANT_NO));

        String message = line.toString();
        if (accessLog) {
            ACCESS.info("{}", message);
        }
        if (elapsed >= slowMs) {
            SLOW.warn("{}", message);
        }
    }

    private static void appendId(StringBuilder line, String key, Object value) {
        if (value instanceof String id && BUSINESS_ID.matcher(id).matches()) {
            line.append(key).append(id);
        }
    }
}
