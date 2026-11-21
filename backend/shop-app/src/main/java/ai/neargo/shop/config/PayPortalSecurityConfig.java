package ai.neargo.shop.config;

import ai.neargo.shop.auth.RequestMetaFilter;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardFilters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * 支付接入层的四条链：渠道回调、开放接口、终端、收银台（ADR-044、ADR-045）。
 *
 * <p>它们不认我方令牌、各自由支付接入守卫把关，不属于三端出口，所以留在启动模块里，
 * 没有随三端的链搬进 shop-gateway（三端与支付运营面的链见 {@code GatewaySecurityConfiguration}）。
 * 原 {@code SecurityConfig} 拆分后剩下的就是这一份。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PayPortalSecurityConfig {

    /**
     * 渠道回调（安全规范 §2.4 / §5.1，G11）：此前 {@code /pay/callback/**} 不匹配任何链，安全全靠 controller 自己。
     * 现在给它一条显式的链：无会话、不认 Bearer、放行到 controller（可信靠验签 + 主动查单）；**不开 CORS**（服务端对服务端）；
     * 用 {@link ai.neargo.shop.auth.RequestMetaFilter} 设可信来源 IP（{@code ClientMeta}），回调记录里的 IP 不再取客户端自报的头。
     * 验签在链上的渠道回调守卫里（开发计划 M4），controller 只回查与入账。
     */
    @Bean
    @Profile({"api", "ops"})
    @Order(5)
    SecurityFilterChain channelCallbackChain(HttpSecurity http, GuardFilters portalFilters) throws Exception {
        return http
                .securityMatcher("/pay/callback/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new RequestMetaFilter("CHANNEL"),
                        SecurityContextHolderFilter.class)
                // 渠道回调守卫（开发计划 M4）：先落原文 → 验签 → 核对收款商户，不过回 FAIL、不进 controller
                .addFilterBefore(portalFilters.forEntry(PortalFace.CALLBACK),
                        SecurityContextHolderFilter.class)
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }

    /**
     * 开放 API（ADR-044；安全规范 §5.1）：{@code /open/pay/**} 只认 AppId + 签名，由支付接入层的鉴权过滤器认人 ——
     * 认不出就 401、请求不进 controller，并写一行请求记录。不认 Bearer、无会话、<b>不开 CORS</b>（服务端对服务端），
     * 来源 IP 经 {@code ClientMeta}。Spring Security 这一层全放行：鉴权在链上的过滤器里已经做完。
     */
    @Bean
    @Profile({"api", "ops"})
    @Order(6)
    SecurityFilterChain openPayChain(HttpSecurity http, GuardFilters portalFilters) throws Exception {
        return http
                .securityMatcher(PortalFace.OPEN.prefix() + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new RequestMetaFilter("PARTNER"), SecurityContextHolderFilter.class)
                .addFilterBefore(portalFilters.forEntry(PortalFace.OPEN), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }

    /**
     * POS 终端（pay-guard TDD §2.2 C；开发计划 M6.2）：{@code /terminal/pay/**} 只认终端号 + 设备密钥签名，由接入守卫的
     * {@code TerminalGuard} 认人（激活接口除外：凭一次性激活码）—— 认不出就 401、不进 controller，并写请求记录。
     * 与开放 API 同形：无会话、不开 CORS（设备对服务端），Spring Security 这一层全放行。
     */
    @Bean
    @Profile({"api", "ops"})
    @Order(9)
    SecurityFilterChain terminalPayChain(HttpSecurity http, GuardFilters portalFilters) throws Exception {
        return http
                .securityMatcher(PortalFace.TERMINAL.prefix() + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new RequestMetaFilter("POS"), SecurityContextHolderFilter.class)
                .addFilterBefore(portalFilters.forEntry(PortalFace.TERMINAL), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }

    /**
     * 收银台（安全规范 §2.5）：顾客不登录也能付，凭证是路径里的收银令牌 / 码号，由收银台服务逐次校验；过滤器照样记请求（令牌只留哈希前缀）。
     * <b>不开 CORS</b>（同源）；{@code X-Frame-Options: DENY} 防被套进别人页面诱导支付；{@code Referrer-Policy: no-referrer}
     * 防令牌经 Referer 外泄；{@code Cache-Control: no-store}。
     */
    @Bean
    @Profile({"api", "ops"})
    @Order(7)
    SecurityFilterChain checkoutChain(HttpSecurity http, GuardFilters portalFilters) throws Exception {
        return http
                .securityMatcher(PortalFace.CHECKOUT.prefix() + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(new RequestMetaFilter("H5"), SecurityContextHolderFilter.class)
                .addFilterBefore(portalFilters.forEntry(PortalFace.CHECKOUT), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .build();
    }
}
