package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.ApiAuthEntryPoint;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.end.GatewayEndSpec;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 按 {@link GatewayEndSpec} 装出一条 Spring Security 过滤器链。三端与支付运营面共用这一份。
 *
 * <p>链的骨架固定：按前缀匹配 → CORS → 关 CSRF（无 cookie 会话，CSRF 不适用）→ 无状态
 * （不建 HttpSession，会话在令牌存储里；小程序端没有 cookie 语义，靠 session 会在 App / H5 / 小程序三端表现不一致）
 * → 放行规则 → 令牌认证 → 端专属过滤器 → 统一的 401 出口。
 *
 * <p>链按前缀分开的收益：拿 C 端令牌打 {@code /ops/**} 在进业务代码之前就 401，不依赖任何入口记得判身份。
 */
public class SpringSecurityAdapter implements GatewaySecurityAdapter<HttpSecurity, SecurityFilterChain> {

    private final CorsConfigurationSource cors;

    public SpringSecurityAdapter(@Qualifier("corsSource") CorsConfigurationSource cors) {
        this.cors = cors;
    }

    @Override
    public SecurityFilterChain install(HttpSecurity http, GatewayEndSpec spec) throws Exception {
        http.securityMatcher(spec.pathPrefix() + "/**");
        if (spec.closed()) {
            return http
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .addFilterBefore(new GatewayEndClosedFilter(), SecurityContextHolderFilter.class)
                    .authorizeHttpRequests(reg -> reg.anyRequest().denyAll())
                    .build();
        }
        http
                .cors(c -> c.configurationSource(cors))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> {
                    if (spec.localDefault() == Access.PUBLIC) {
                        reg.anyRequest().permitAll();
                        return;
                    }
                    if (!spec.publicPaths().isEmpty()) {
                        reg.requestMatchers(spec.publicPaths().toArray(String[]::new)).permitAll();
                    }
                    /*
                     * **异步与错误分派放行。** 不放的话，SSE（/ops/stream）每次到点
                     * 都会被当成匿名拒掉：令牌过滤器继承 OncePerRequestFilter、默认跳过
                     * 异步分派，且身份只写进 SecurityContextHolder、不存进请求属性 ——
                     * 于是 30 分钟一到，那次 ASYNC 分派拿到的是空身份。而 SSE 的响应
                     * 早已提交、转不成 401，异常一路抛到 Tomcat 记 ERROR 带整段堆栈。
                     *
                     * 2026-09-14 生产实测：当天全部 10 条 ERROR 都是它、占日志字节 46%，
                     * 恰好每 30 分钟 2 条（ASYNC 一次、随后的 ERROR 分派一次）。
                     * 「控制台只留 ERROR 进 journal」那条通道因此 100% 是噪音。
                     *
                     * **这样放行是安全的**：ASYNC 分派只能延续一个已经在 REQUEST 分派上
                     * 过了鉴权的请求，外面打不进来；ERROR 分派只渲染错误页。
                     * OpsStreamTest.asyncDispatchAfterTimeoutKeepsIdentity 钉住它。
                     */
                    reg.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    reg.anyRequest().authenticated();
                });

        AbstractTokenAuthFilter authenticator = spec.authenticator();
        http.addFilterBefore(authenticator, UsernamePasswordAuthenticationFilter.class);
        // 端专属过滤器必须在认证之后：它们要用登录态（比如按登录人解析经营侧作用域）
        Class<? extends Filter> previous = authenticator.getClass();
        for (Filter filter : spec.contextFilters()) {
            http.addFilterAfter(filter, previous);
            previous = filter.getClass();
        }
        // 401 要带包体，且区分「没登录」与「登录过期」—— 见 ApiAuthEntryPoint
        http.exceptionHandling(e -> e.authenticationEntryPoint(new ApiAuthEntryPoint()));
        return http.build();
    }
}
