package ai.neargo.shop.food.server.config;

import ai.neargo.shop.food.scope.FoodScopeBodyScanner;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Rejected;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /internal/food/**} 的第二道：令牌之后，验宿主签发的范围凭证（TDD-餐饮系统-内部调用签名凭证）。
 *
 * <p>先把请求体整个读进来 —— 摘要要绑请求体，且 Controller 之后还要原样再读一遍。
 * 凭证不成立 401，凭证成立但与请求体的主体 / 门店 / 操作人不符 403，都不进 Controller。
 */
public final class FoodScopeVerificationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(FoodScopeVerificationFilter.class);
    private static final String PREFIX = "/internal/food/";

    private final List<byte[]> keys;
    private final LongSupplier nowEpochSeconds;

    public FoodScopeVerificationFilter(List<byte[]> keys, LongSupplier nowEpochSeconds) {
        this.keys = keys.stream().filter(java.util.Objects::nonNull).toList();
        this.nowEpochSeconds = nowEpochSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        try {
            var claims = FoodScopeCredentials.verify(request.getHeader(FoodScopeCredentials.HEADER), keys,
                    FoodScopeCredentials.digest(request.getMethod(), path, body), nowEpochSeconds.getAsLong());
            FoodScopeCredentials.requireBodyMatches(claims, FoodScopeBodyScanner.scan(body));
        } catch (Rejected rejected) {
            log.warn("food-server 拒绝内部调用 {} {}：{}", request.getMethod(), request.getRequestURI(),
                    rejected.getMessage());
            response.sendError(rejected.status());
            return;
        }
        chain.doFilter(new CachedBody(request, body), response);
    }

    private static final class CachedBody extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String charset = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
                    charset == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(charset)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
