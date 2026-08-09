package ai.neargo.shop.config;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.OperatorTokenAuthFilter;
import ai.neargo.shop.auth.TokenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 三条过滤器链 —— **越权防线的第 ① 道**（TDD-backend §5.2）。
 *
 * <p>链按前缀分开的收益：拿 C 端 token 打 {@code /ops/**} 在进业务代码之前就 401，
 * 不依赖任何 Controller 记得判身份。若合成一条链靠参数区分，这个保证就没了。
 *
 * <p>无状态：不建 HttpSession（{@code STATELESS}），会话在 {@link TokenStore} 里。
 * 小程序端没有 cookie 语义，靠 session 会在 App/H5/小程序三端表现不一致。
 */
@Configuration
@EnableMethodSecurity   // 开 @PreAuthorize("@perm.can('码')")，仅 /ops 使用
public class SecurityConfig {

    /**
     * C 端与 B 端：登录可选（游客可逛），属主鉴权在 Service 层。
     *
     * <p>{@code @Profile("api")}（S8）：ops 部署不装这条链。但真正的隔离不靠它——
     * {@code /mp/**} 的 Controller 自己也带 {@code @Profile("api")}，
     * 在 ops 部署里**路由压根不存在**，打过去是 404 而不是 401。
     * 少一条链只是少一层过滤器；少一个路由才是少一个攻击面。
     */
    @Bean
    @Profile("api")
    @Order(1)
    SecurityFilterChain consumerChain(HttpSecurity http, TokenStore tokenStore,
                                      ObjectProvider<BizIdentityResolver> resolver) throws Exception {
        return http
                .securityMatcher("/mp/**", "/biz/**")
                .csrf(csrf -> csrf.disable())          // 无 cookie 会话，CSRF 不适用
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        // 登录接口本身当然不能要求已登录 —— 两条都是登录入口，写在最前面。
                        // staff-login 是**员工独立登录**（App 路径）：他可能根本没有 C 端账号，
                        // 要求先登录才能登录是个死循环
                        .requestMatchers("/biz/auth/login", "/biz/auth/staff-login").permitAll()
                        // /biz/** 其余一律必须登录；具体作用域由 BizContext + DataScope 裁剪
                        .requestMatchers("/biz/**").authenticated()
                        // /mp/** 一律放行到业务层：游客能逛商品，但下单接口自己 requireUser()
                        .anyRequest().permitAll())
                .addFilterBefore(new ConsumerTokenAuthFilter(tokenStore),
                        UsernamePasswordAuthenticationFilter.class)
                // BizContextFilter 必须在认证之后：它要用登录态去解析经营侧作用域
                .addFilterAfter(new BizContextFilter(resolver.getIfAvailable(() -> BizIdentityResolver.NONE)),
                        ConsumerTokenAuthFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /** 运营端：全部需要登录 + RBAC。仅 ops 部署装配，且 ops 只在内网可达。 */
    @Bean
    @Profile("ops")
    @Order(2)
    SecurityFilterChain operatorChain(HttpSecurity http, TokenStore tokenStore) throws Exception {
        return http
                .securityMatcher("/ops/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/ops/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new OperatorTokenAuthFilter(tokenStore),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /** 公共与回调：{@code /callback/**} 各自验签，不走 Bearer（[API 清单 §5.2]）。 */
    @Bean
    @Profile({"api", "ops"})
    @Order(3)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        return http
                // /uploads/** 是商品图：**游客必须能看**，否则未登录逛首页全是裂图。
                // 图本身不是秘密（它就是要给买家看的），访问控制在"能不能传"那一侧
                .securityMatcher("/common/**", "/callback/**", "/actuator/**", "/uploads/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }
}
