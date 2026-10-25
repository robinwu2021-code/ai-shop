package ai.neargo.shop.config;

import ai.neargo.shop.print.api.PrintAgentAuthenticationService;
import ai.neargo.shop.printbridge.PrintAgentAuthenticationFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
        return http.securityMatcher(
                        "/device/print/**", "/callbacks/print/**", "/private/print-artifacts")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(r -> r.anyRequest().permitAll())
                .addFilterBefore(
                        new PrintAgentAuthenticationFilter(a, json, https),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
