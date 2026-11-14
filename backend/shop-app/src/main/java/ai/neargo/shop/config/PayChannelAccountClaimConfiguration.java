package ai.neargo.shop.config;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.payclient.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
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

/** N2 precedes the authentication-only legacy BIZ relay. No embedded PAY PortalGuard. */
@Configuration(proxyBeanMethods=false)
@Profile("api")
@ConditionalOnProperty(name="shop.pay.channel-account-claims.enabled",havingValue="true")
public class PayChannelAccountClaimConfiguration {
    @Bean PayChannelAccountClaimForwarder channelAccountClaimForwarder(
            @Value("${shop.pay.channel-account-claims.base-url}") URI origin) {
        return new PayChannelAccountClaimForwarder(origin);
    }
    @Bean @Order(-1)
    SecurityFilterChain channelAccountClaimChain(HttpSecurity http,JdbcClient authJdbcClient,Environment env,
            BizIdentityResolver resolver,BizContextFilter.Admission admission,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
            @Qualifier("corsSource") CorsConfigurationSource cors) throws Exception {
        if(!TokenStoreSelection.usesDb(env,Realm.MERCHANT)) throw new IllegalStateException("channel account claims require merchant DB sessions");
        return http.securityMatcher(PayChannelAccountClaimForwarder.BASE,PayChannelAccountClaimForwarder.BASE+"/**")
                .cors(c->c.configurationSource(cors)).csrf(c->c.disable()).requestCache(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).headers(c->c.disable())
                .authorizeHttpRequests(c->c.anyRequest().authenticated())
                .addFilterBefore(new PayBizAuthenticationFilter(new PayBizSessionVerifier(authJdbcClient),errors),UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RequestMetaFilter("APP_BIZ"),PayBizAuthenticationFilter.class)
                .addFilterAfter(new BizContextFilter(resolver,admission),PayBizAuthenticationFilter.class)
                .exceptionHandling(c->c.authenticationEntryPoint(new ApiAuthEntryPoint())).build();
    }
    // When the old relay is enabled, its existing resolver already preserves all /biz/pay/v2 bytes.
    @Bean(name="multipartResolver")
    @ConditionalOnProperty(name="shop.pay.biz-onboarding.enabled",havingValue="false",matchIfMissing=true)
    StandardServletMultipartResolver channelAccountClaimMultipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                String path=request.getRequestURI().substring(request.getContextPath().length());
                return !path.equals(PayChannelAccountClaimForwarder.BASE)
                        && !path.startsWith(PayChannelAccountClaimForwarder.BASE+"/") && super.isMultipart(request);
            }
        };
    }
}
