package ai.neargo.shop.auth;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.RequestLogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 三端认证过滤器的公共骨架。**每端一个子类，差别只有三处**：
 * 认哪个 {@link Realm}、审计里记什么操作端、怎么把会话身份变成一个可判权的身份。
 *
 * <h2>为什么要有这个基类</h2>
 * 三端的认证流程逐字相同：取 Bearer → 查会话 → 分辨「没带令牌」与「带了但会话没了」→
 * 放进 SecurityContext → 设数据域 → 收尾清理。
 * 抄三份的话，将来任何一处改动（比如新增一种失败态）都要记得改三处 ——
 * 而漏掉的那一处**不会报错**，只会让某一端的行为与另外两端悄悄不同。
 *
 * <h2>令牌池隔离在这里落地</h2>
 * {@link #realm} 不符时**什么都不做**：既不放行也不报错，
 * 请求会在后面被 Spring Security 判成未认证。
 * 拿 C 端令牌打 {@code /ops/**}，最终是 401 而不是「越权成功」。
 */
public abstract class AbstractTokenAuthFilter extends OncePerRequestFilter {

    /**
     * 「带了令牌但会话没了」的标记。{@code ApiAuthEntryPoint} 与
     * {@code GlobalExceptionHandler} 据此把「过期」与「没权限」分开 ——
     * 一个让人重新登录，一个让人去找老板要权限。
     */
    public static final String TOKEN_EXPIRED_ATTR = "shop.auth.tokenExpired";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AbstractTokenAuthFilter.class);

    private final TokenStore tokenStore;
    private final Realm realm;

    protected AbstractTokenAuthFilter(TokenStore tokenStore, Realm realm) {
        this.tokenStore = tokenStore;
        this.realm = realm;
    }

    /** 审计用的操作端标识，落进 {@link RequestMetaContext}。 */
    protected abstract String clientCode(HttpServletRequest req);

    /**
     * 把会话里的身份变成**此刻**可判权的身份。
     *
     * <p>三端的差别全在这一个方法里：C 端与 B 端原样用，
     * 运营端还要把角色与数据域现算一遍（见各自子类）。
     */
    protected abstract Authenticated authenticate(LoginUser sessionUser);

    /** 认证结果：判权用的身份 + 授予的权威。 */
    protected record Authenticated(LoginUser user, List<SimpleGrantedAuthority> authorities) {
    }

    @Override
    protected final void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                          FilterChain chain) throws ServletException, IOException {
        /*
         * IP 在**每条链**上都要设。
         *
         * 它此前只在 ops 链上设，于是 /mp 与 /biz 的领域代码拿不到 IP ——
         * 发码限流的「同 IP 每小时 N 次」这一道就是废的（拿不到 IP 时它放行），
         * 而换着手机号刷码的机器人**只会撞这一道**：前两道按号计数，对它无效。
         *
         * 放在最前面而不是认证之后：认证本身将来可能要用到 IP（比如异地登录），
         * 而「设得早一点」没有任何代价。
         */
        RequestMetaContext.set(ClientMeta.of(req, clientCode(req)));

        boolean scopeSet = false;
        try {
            String token = bearer(req);
            Authentication authentication = token == null ? null : resolveAuthentication(req, token);
            if (authentication != null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                DataScopeContext.set(((LoginUser) authentication.getPrincipal()).dataScope());
                scopeSet = true;
            }
            chain.doFilter(req, resp);
        } catch (AuthenticationStorageUnavailable e) {
            log.error("认证存储不可用，{} 端暂时无法认证 · {} {} · {}", realm, RequestLogContext.method(req),
                    RequestLogContext.route(req, 503), e.getCause().getClass().getSimpleName());
            resp.setHeader("Retry-After", "5");
            ApiAuthEntryPoint.writeError(req, resp, 503, ErrorCode.INTERNAL_ERROR);
        } finally {
            RequestMetaContext.clear();
            if (scopeSet) {
                DataScopeContext.clear();
            }
        }
    }

    protected Authentication resolveAuthentication(HttpServletRequest req, String token) {
        TokenStore.SessionData session;
        try {
            session = tokenStore.get(token).orElse(null);
        } catch (RuntimeException e) {
            throw new AuthenticationStorageUnavailable(e);
        }
        if (session == null) {
            req.setAttribute(TOKEN_EXPIRED_ATTR, Boolean.TRUE);
            return null;
        }
        return session.user().realm() == realm ? authentication(authenticate(session.user()), token) : null;
    }

    protected static final class AuthenticationStorageUnavailable extends RuntimeException {
        public AuthenticationStorageUnavailable(RuntimeException cause) {
            super(cause);
        }
    }

    protected final Authentication authentication(Authenticated identity, Object details) {
        var result = new UsernamePasswordAuthenticationToken(identity.user(), null, identity.authorities());
        result.setDetails(details);
        return result;
    }

    /** 从 {@code Authorization: Bearer xxx} 里取令牌；没有或为空返回 null。 */
    public static String bearer(HttpServletRequest req) {
        return bearer(req.getHeader("Authorization"));
    }

    /** Parse a bound Authorization header with the same semantics as the filter entry point. */
    public static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
