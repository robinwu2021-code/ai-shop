package ai.neargo.shop.config;

import ai.neargo.shop.auth.ApiAuthEntryPoint;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.RequestMetaFilter;
import ai.neargo.shop.gateway.biz.PayBizPassThrough;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.payclient.PayBizAuthenticationFilter;
import ai.neargo.shop.payclient.PayBizSessionVerifier;
import ai.neargo.shop.payclient.PayBizSourceDelegation;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
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

  /** 店主名下支付商户由 ai-shop 授权；来源调用方凭据与店铺同步共用（shop.nearpay.store-sync.caller / credential）。 */
  @Bean
  PayBizSourceDelegation payBizSourceDelegation(
      ObjectProvider<MerchantEntityService> entities,
      ObjectProvider<PaySourceLinkApi> links,
      ObjectProvider<StoreSyncProperties> sync) {
    return PayBizSourceDelegation.of(entities, links, sync);
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
      @Qualifier("corsSource") CorsConfigurationSource cors,
      ObjectProvider<PayBizPassThrough> payBiz)
      throws Exception {
    // 开了 B 端支付透传（TDD-支付三端经网关透传与权限归属 §3.1）：/biz/pay/v2/** 认证照旧走这条链，
    // 认证之后整前缀原样转给支付服务，不再进下面的转发入口（不改写商户号、不加来源委托头）。
    PayBizPassThrough passThrough = payBiz.getIfAvailable();
    if (passThrough != null) http.addFilterAfter(passThrough.filter(), AuthorizationFilter.class);
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
