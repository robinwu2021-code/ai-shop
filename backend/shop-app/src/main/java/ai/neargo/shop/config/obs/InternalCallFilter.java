package ai.neargo.shop.config.obs;

import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 业务侧的内部入口（ADR-045 G9；TDD-支付服务-接入守卫模块pay-guard §2.7.4；开发计划 M2.6）：与支付侧
 * {@code InternalGuard} 同一套规则 —— <b>只识别、不拒绝</b>。读 {@code X-Internal-Caller} 与 {@code X-Call-Source}，
 * 放进请求属性给访问日志（{@link ApiAccessLogFilter}）；处理期间把收到的来源绑成 {@link CallSources} 的来源，
 * 这次处理里再调别的服务（比如回头调支付）时带的是同一个追踪号与上游接口。
 *
 * <p>挂在整个 {@code /internal/**}（设计稿写的 {@code /internal/shop/v1} 在代码里不存在：业务服务的内部端点分在
 * {@code /internal/core}、{@code /internal/trade}、{@code /internal/job} 等前缀下）。各端点原有的令牌校验照旧，本过滤器不碰。
 */
public class InternalCallFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = InternalCallFilter.class.getName() + ".caller";
    public static final String SOURCE_ATTRIBUTE = InternalCallFilter.class.getName() + ".source";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return shouldNotFilterPath(request.getRequestURI().substring(request.getContextPath().length()));
    }

    public boolean shouldNotFilterPath(String path) {
        return !path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(InternalHeaders.CALLER);
        req.setAttribute(CALLER_ATTRIBUTE, header == null || header.isBlank() ? CallSources.UNKNOWN : header.trim());
        CallSource source = CallSourceCodec.decode(req.getHeader(InternalHeaders.CALL_SOURCE)).orElse(null);
        req.setAttribute(SOURCE_ATTRIBUTE, source != null ? source
                : new CallSource(CallSources.UNKNOWN, null, null, null, null, null, CallSources.UNKNOWN, null, null, null,
                        null));
        if (source == null) {
            chain.doFilter(req, resp);
            return;
        }
        try (CallSources.Scope ignored = CallSources.bind(new CallSources.Origin(source.traceId(), source.originApi(),
                source.originClient(), source.originIp(), source.originUaDigest(), source.operatorNo()))) {
            chain.doFilter(req, resp);
        }
    }
}
