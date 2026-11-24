package ai.neargo.shop.config;

import ai.neargo.shop.print.api.PrintAgentAuthenticationService;
import ai.neargo.shop.printbridge.PrintAgentAuthenticationFilter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("api")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
public class PrintSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain printDeviceChain(
            HttpSecurity http,
            PrintAgentAuthenticationService a,
            ObjectMapper json,
            @Value("${shop.print.agent.require-https:true}") boolean https)
            throws Exception {
        return http.securityMatcher("/device/print/**", "/callbacks/print/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(r -> r.anyRequest().permitAll())
                .addFilterBefore(
                        new PrintAgentAuthenticationFilter(a, json, https),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * 签名下载链接（{@code /private/print-artifacts}）。与设备链同样无会话、凭签名放行，但<b>开 CORS</b>（同 {@code /biz} 的白名单）：
     * B 端发票打印模拟要在 H5 里把 PDF 取成 blob 再内嵌显示（TDD-发票模块 §21）。此前它挂在设备链上，那条链不处理 CORS，
     * 浏览器读不到响应。链接本身仍是带签名、60 秒过期；{@code X-Frame-Options} 与 {@code attachment} 不变。
     */
    @Bean
    @Order(0)
    SecurityFilterChain printArtifactDownloadChain(
            HttpSecurity http,
            PrintAgentAuthenticationService a,
            ObjectMapper json,
            @Qualifier("corsSource") CorsConfigurationSource cors,
            @Value("${shop.print.agent.require-https:true}") boolean https)
            throws Exception {
        return http.securityMatcher("/private/print-artifacts")
                .cors(c -> c.configurationSource(cors))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(r -> r.anyRequest().permitAll())
                .addFilterBefore(
                        new PrintAgentAuthenticationFilter(a, json, https),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
