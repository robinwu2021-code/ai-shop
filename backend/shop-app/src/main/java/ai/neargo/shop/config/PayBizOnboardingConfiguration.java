package ai.neargo.shop.config;

import ai.neargo.shop.auth.ApiAuthEntryPoint;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.RequestMetaFilter;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.payclient.PayBizAuthenticationFilter;
import ai.neargo.shop.payclient.PayBizSessionVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** BIZ identity bridge and raw relay; local shop selection is not payment membership. */
@Configuration(proxyBeanMethods = false)
@Profile("api")
@ConditionalOnProperty(name = "shop.pay.biz-onboarding.enabled", havingValue = "true")
public class PayBizOnboardingConfiguration {
  @Bean
  PayBizSessionVerifier payBizSessionVerifier(JdbcClient authJdbcClient, Environment env) {
    if (!TokenStoreSelection.usesDb(env, Realm.MERCHANT))
      throw new IllegalStateException("near-pay BIZ requires merchant DB sessions");
    return new PayBizSessionVerifier(authJdbcClient);
  }

  @Bean
  BizOnboardingForwarder bizOnboardingForwarder(
      @Value("${shop.pay.biz-onboarding.base-url}") URI origin) {
    return new BizOnboardingForwarder(origin);
  }

  @Bean
  @Order(0)
  SecurityFilterChain payBizOnboardingChain(
      HttpSecurity http,
      PayBizSessionVerifier sessions,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
      @Qualifier("corsSource") CorsConfigurationSource cors)
      throws Exception {
    return http.securityMatcher("/biz/auth/session", "/biz/pay/v2/**")
        .cors(c -> c.configurationSource(cors))
        .csrf(c -> c.disable())
        .requestCache(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Keep the upstream's allowlisted cache/security headers unchanged.
        .headers(c -> c.disable())
        .authorizeHttpRequests(c -> c.anyRequest().authenticated())
        .addFilterBefore(
            new PayBizAuthenticationFilter(sessions, errors),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new RequestMetaFilter("APP_BIZ"), PayBizAuthenticationFilter.class)
        .exceptionHandling(c -> c.authenticationEntryPoint(new ApiAuthEntryPoint()))
        .build();
  }

  @Bean(name = "multipartResolver")
  StandardServletMultipartResolver payBizMultipartResolver() {
    return new StandardServletMultipartResolver() {
      @Override
      public boolean isMultipart(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // The relay checks total/per-file limits as it captures the original bytes.
        // Do not let DispatcherServlet consume, spool or re-encode the incoming parts.
        return !path.startsWith("/biz/pay/v2/") && super.isMultipart(request);
      }
    };
  }
}
