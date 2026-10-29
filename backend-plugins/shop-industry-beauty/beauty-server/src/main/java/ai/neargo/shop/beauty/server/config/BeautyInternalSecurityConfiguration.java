package ai.neargo.shop.beauty.server.config;

import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.job.api.JobHttpPaths;
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

/** 服务密钥只授予内部入口访问；业务资源可信范围必须由各内部合同另外校验。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class BeautyInternalSecurityConfiguration {

    @Bean
    SecurityFilterChain beautyInternalChain(HttpSecurity http,
            @Value("${shop.services.internal-token:}") String token) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, failure) -> response.sendError(401)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(JobHttpPaths.DECLARATIONS, JobHttpPaths.RUN).permitAll()
                        .requestMatchers("/internal/beauty/**").access((authentication, context) ->
                                new AuthorizationDecision(trusted(token,
                                        context.getRequest().getHeader(InternalHttp.TOKEN_HEADER))))
                        .requestMatchers("/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    private static boolean trusted(String expected, String given) {
        return expected != null && !expected.isBlank() && given != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        given.getBytes(StandardCharsets.UTF_8));
    }
}
