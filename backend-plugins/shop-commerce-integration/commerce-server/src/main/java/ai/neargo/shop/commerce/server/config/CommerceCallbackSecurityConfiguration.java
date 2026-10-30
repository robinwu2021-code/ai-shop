package ai.neargo.shop.commerce.server.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Only the two-segment POST protocol callback is public; the controller verifies the original
 * platform signature.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.commerce.callbacks.enabled", havingValue = "true")
public class CommerceCallbackSecurityConfiguration {
    @Bean
    @Order(-1)
    SecurityFilterChain commerceCallbacks(HttpSecurity http) throws Exception {
        return http.securityMatcher(
                        request ->
                                "POST".equals(request.getMethod())
                                        && request.getRequestURI()
                                                .matches("/callback/commerce/[^/]+/[^/]+"))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
