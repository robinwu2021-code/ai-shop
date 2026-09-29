package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.RequestMetaContext;
import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
import ai.neargo.svc.client.ServiceCallException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 认令牌。<b>路径决定认哪一种</b>：
 * <ul>
 *   <li>{@code /elec/c/**}、{@code /elec/b/**}：C 端令牌 —— B 端与 C 端是同一个账号</li>
 *   <li>{@code /elec/ops/**}：运营令牌</li>
 * </ul>
 * 类型不对的令牌当作没带（于是要登录的接口回 401），与主系统「跨池令牌在前缀那一道就被拒」同一个口径。
 *
 * <p><b>这里不拦请求</b>：认出来就放进 SecurityContext，认不出就匿名往下走，
 * 要不要登录由各接口自己取当前用户时决定（查料号游客可用，询价要登录）。
 * <b>唯一的例外是主系统调不通</b>：带了令牌、主系统又问不到，这时回 503 ——
 * 回 401 的话端上会清掉令牌让用户重新登录，而他什么都没做错。查料号例外：匿名照查。
 */
public class ElecTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ElecTokenFilter.class);

    private final ElecSessionResolver sessions;
    private final ObjectMapper json;

    public ElecTokenFilter(ElecSessionResolver sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        RequestMetaContext.set(new RequestMetaContext.Meta(clientIp(req), "ELEC", null));
        try {
            String token = bearer(req);
            if (token != null) {
                ElecInternal.Session s;
                try {
                    s = sessions.resolve(token);
                } catch (ServiceCallException e) {
                    log.warn("主系统认令牌调不通 path={} {}", req.getRequestURI(), e.toString());
                    if (!req.getRequestURI().startsWith("/elec/c/part")) {
                        unavailable(res);
                        return;
                    }
                    s = ElecInternal.Session.invalid(false);
                }
                authenticate(req, s);
            }
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
            RequestMetaContext.clear();
        }
    }

    private void authenticate(HttpServletRequest req, ElecInternal.Session s) {
        if (!s.valid()) {
            if (s.expired()) {
                // 与主系统同一个标记：全局异常处理据此回 10402「登录已过期」而不是 10401
                req.setAttribute(AbstractTokenAuthFilter.TOKEN_EXPIRED_ATTR, Boolean.TRUE);
            }
            return;
        }
        boolean opsPath = req.getRequestURI().startsWith("/elec/ops/");
        LoginUser user;
        String role;
        if ("OPERATOR".equals(s.realm()) && opsPath) {
            user = LoginUser.operator(s.userNo(), s.nickname(), List.of(), s.perms() == null ? List.of() : s.perms());
            role = "ROLE_OPERATOR";
        } else if ("CONSUMER".equals(s.realm()) && !opsPath) {
            user = LoginUser.consumer(s.userNo(), s.nickname());
            role = "ROLE_CONSUMER";
        } else {
            return;   // 类型不对的令牌当作没带
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private void unavailable(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        ErrorCode c = ErrorCode.INTERNAL_ERROR;
        res.getWriter().write(json.writeValueAsString(new ApiResult<>(c.code(), Messages.get(c.msgKey()), null)));
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            return null;
        }
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    /** 与主系统 ClientMeta 同一个口径：只信 X-Forwarded-For 最左一跳（nginx 覆写它） */
    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
