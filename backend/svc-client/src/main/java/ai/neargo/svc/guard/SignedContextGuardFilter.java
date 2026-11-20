package ai.neargo.svc.guard;

import ai.neargo.svc.context.SignedContextCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微服务侧的守卫：拦内部入口，验签名上下文，把它放进当前线程（TDD-网关层与统一权限 §2.7）。
 *
 * <p>三档：{@code off} 不验；{@code observe} 验了只记日志、照常放行，用来在打开拒绝之前看清有多少调用对不上；
 * {@code enforce} 对不上回 401。只依赖 Servlet，不依赖 Spring 容器与安全框架，所以各服务的构建规则都能接受它。
 */
public class SignedContextGuardFilter extends HttpFilter {

    private static final Logger log = LoggerFactory.getLogger(SignedContextGuardFilter.class);
    /** 请求体读进内存算摘要，超过这个大小不读：按对不上处理。 */
    static final int MAX_BODY = 1024 * 1024;

    private final SignedContextVerifier verifier;
    private final String mode;
    private final String pathPrefix;

    /** @param pathPrefix 只拦这个前缀下的地址，如 {@code /internal/} */
    public SignedContextGuardFilter(SignedContextVerifier verifier, String mode, String pathPrefix) {
        this.verifier = verifier;
        this.mode = mode == null ? "off" : mode.toLowerCase();
        this.pathPrefix = pathPrefix;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("off".equals(mode) || !path(request).startsWith(pathPrefix)) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        String target = path(request) + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        SignedContextVerifier.Result result = body.length > MAX_BODY
                ? new SignedContextVerifier.Result(SignedContextVerifier.Outcome.BODY_MISMATCH, java.util.Optional.empty())
                : verifier.verify(request.getHeader(SignedContextCodec.HEADER_CONTEXT),
                        request.getHeader(SignedContextCodec.HEADER_SIGNATURE), request.getMethod(), target, body,
                        System.currentTimeMillis());
        if (!result.ok()) {
            if ("enforce".equals(mode)) {
                log.warn("签名上下文验证不过，已拒绝 {} {} · {}", request.getMethod(), target, result.outcome());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            log.warn("签名上下文验证不过（只比对，已放行）{} {} · {}", request.getMethod(), target, result.outcome());
        }
        try {
            result.context().ifPresent(SignedContextHolder::set);
            chain.doFilter(new Replay(request, body), response);
        } finally {
            SignedContextHolder.clear();
        }
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** 请求体已经读过一遍，给后面再读一遍。 */
    private static final class Replay extends HttpServletRequestWrapper {

        private final byte[] body;

        Replay(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
