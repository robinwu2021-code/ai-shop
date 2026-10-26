package ai.neargo.shop.food.server.config;

import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.svc.InternalHttp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * 两道：共享令牌只说明「是平台内部进程」；令牌之后 {@link FoodScopeVerificationFilter} 验宿主签发的范围凭证，
 * 并核对请求体里的主体 / 门店 / 操作人（TDD-餐饮系统-内部调用签名凭证）。密钥没配或太短时一律拒绝。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class FoodInternalSecurityConfiguration {

    @Bean
    SecurityFilterChain foodInternalChain(HttpSecurity http,
            @Value("${shop.services.internal-token:}") String token,
            @Value("${shop.food.scope-key:}") String scopeKey,
            @Value("${shop.food.scope-key-previous:}") String previousScopeKey) throws Exception {
        var scopeKeys = new java.util.ArrayList<byte[]>();
        scopeKeys.add(FoodScopeCredentials.key(scopeKey));
        scopeKeys.add(FoodScopeCredentials.key(previousScopeKey));
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, failure) -> response.sendError(401)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/food/**").access((authentication, context) ->
                                new AuthorizationDecision(trusted(token,
                                        context.getRequest().getHeader(InternalHttp.TOKEN_HEADER))))
                        .requestMatchers("/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .anyRequest().denyAll())
                .addFilterAfter(new FoodScopeVerificationFilter(scopeKeys,
                        () -> System.currentTimeMillis() / 1000), AuthorizationFilter.class)
                .build();
    }

    private static boolean trusted(String expected, String given) {
        return expected != null && !expected.isBlank() && given != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        given.getBytes(StandardCharsets.UTF_8));
    }
}
