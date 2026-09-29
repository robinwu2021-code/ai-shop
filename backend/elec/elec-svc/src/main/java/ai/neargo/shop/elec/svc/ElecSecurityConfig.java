package ai.neargo.shop.elec.svc;

import ai.neargo.shop.auth.ApiAuthEntryPoint;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 一条链管全部路径。<b>不在链上挡「要不要登录」</b>：查料号游客可用、询价要登录，
 * 由各接口取当前用户时决定（取不到抛 401）—— 与主系统 C 端链同一个做法。
 * 运营端接口的权限码由 {@code ElecOpsGuard} 在接口里判。
 *
 * <p>无 cookie 会话，CSRF 不适用；同域经 nginx，不开 CORS。
 */
@Configuration
public class ElecSecurityConfig {

    @Bean
    SecurityFilterChain elecChain(HttpSecurity http, ElecSessionResolver sessions, ObjectMapper json)
            throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(new ElecTokenFilter(sessions, json), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new ApiAuthEntryPoint()))
                .build();
    }
}
