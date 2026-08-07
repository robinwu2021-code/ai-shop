package ai.neargo.shop.config;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.OperatorTokenAuthFilter;
import ai.neargo.shop.auth.TokenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    /** C 端：登录可选（游客可逛），属主鉴权在 Service 层。 */
    @Bean
    @Order(1)
    SecurityFilterChain consumerChain(HttpSecurity http, TokenStore tokenStore,
                                      ObjectProvider<BizIdentityResolver> resolver) throws Exception {
        return http
                .securityMatcher("/mp/**", "/biz/**")
                .csrf(csrf -> csrf.disable())          // 无 cookie 会话，CSRF 不适用
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        // /biz/** 必须登录；具体作用域由 BizContext + DataScope 裁剪
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

    /** 运营端：全部需要登录 + RBAC。 */
    @Bean
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
    @Order(3)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/common/**", "/callback/**", "/actuator/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }
}
