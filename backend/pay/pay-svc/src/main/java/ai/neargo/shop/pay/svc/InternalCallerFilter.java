package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PaymentInternalPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 只挂 {@code /internal/pay/v1/**}：按 {@code X-Internal-Caller} + 凭据识别调用方，认不出一律 401（原因只进日志）；
 * 认得就把调用方码放进请求属性，端点据此限定业务类型（代码清单 §5.3）。
 */
@Component
public class InternalCallerFilter extends OncePerRequestFilter {

    /** 端点读这个属性拿调用方码；属性只能由本过滤器写 */
    public static final String ATTRIBUTE = InternalCallerFilter.class.getName() + ".caller";

    private final InternalCallerRegistry callers;

    public InternalCallerFilter(InternalCallerRegistry callers) {
        this.callers = callers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PaymentInternalPaths.PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var caller = callers.resolve(request.getHeader(PaymentInternalPaths.CALLER),
                request.getHeader(PaymentInternalPaths.CREDENTIAL));
        if (caller.isEmpty()) {
            logger.warn("[internal-caller] 认不出的调用方 " + request.getHeader(PaymentInternalPaths.CALLER)
                    + " → " + request.getRequestURI());
            response.setStatus(401);
            return;
        }
        request.setAttribute(ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }
}
