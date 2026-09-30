package ai.neargo.shop.elec.svc;

import ai.neargo.shop.auth.ApiAuthEntryPoint;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * 一条链管全部路径。<b>不在链上挡「要不要登录」</b>：查料号游客可用、询价要登录，
 * 由各接口取当前用户时决定（取不到抛 401）—— 与主系统 C 端链同一个做法。
 * 运营端接口的权限码由 {@code ElecOpsGuard} 在接口里判。
 *
 * <p>无 cookie 会话，CSRF 不适用；同域经 nginx，不开 CORS。
 *
 * <p><b>唯一的例外是本机开发</b>：运营端（ops-web，静态导出、没有开发代理）在另一个端口直连这里，
 * 浏览器会拦跨域。{@code elec.dev-cors-origins} 列出放行的源（逗号分隔），<b>默认空 = 不开</b> ——
 * 生产不配它，链上就没有 CORS 这一段（{@code ElecDevCorsOffTest} 钉着）。
 */
@Configuration
public class ElecSecurityConfig {

    @Bean
    SecurityFilterChain elecChain(HttpSecurity http, ElecSessionResolver sessions, ObjectMapper json,
                                  @Value("${elec.dev-cors-origins:}") String devCorsOrigins)
            throws Exception {
        List<String> origins = Arrays.stream(devCorsOrigins.split(","))
                .map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (origins.isEmpty()) {
            http.cors(c -> c.disable());
        } else {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(origins);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("*"));
            UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
            src.registerCorsConfiguration("/elec/**", cfg);
            http.cors(c -> c.configurationSource(src));
        }
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
