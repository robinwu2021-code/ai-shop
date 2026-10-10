package ai.neargo.shop.config.obs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * 可观测接线（TDD-接口耗时与慢日志）。
 */
@Configuration
public class ObsConfig {

    /**
     * <b>全局注册，而不是挂进 SecurityFilterChain。</b>
     *
     * <p>`SecurityConfig` 里有 <b>5 条</b> 链（/biz、/mp、/ops…）。挂进链里就得挂五遍，
     * 而且<b>漏掉不匹配任何一条链的请求</b> —— 那恰恰是出问题时最想看的那些。
     *
     * <p><b>代价必须说清楚</b>：`MockMvc.webAppContextSetup` <b>不装</b>
     * `FilterRegistrationBean` 注册的过滤器。拿 MockMvc 写本过滤器的用例会全绿
     * 而什么都没测到 —— 所以它的测试起 `RANDOM_PORT` 走真实链路。
     *
     * <p>`HIGHEST_PRECEDENCE`：要量的是**整个请求**的耗时，包括认证、数据域解析那几层。
     * 放里面就只量到业务方法，而「登录态解析变慢」恰恰是查不出来最难受的一类。
     *
     * <p>⚠️ `@Value` <b>不做松绑定</b>：下面占位符里的键名必须与 `application.yml`
     * 里逐字一致（写成 `slowMs` 配了等于没配，而且不报错）。
     */
    @Bean
    public FilterRegistrationBean<ApiAccessLogFilter> apiAccessLogFilter(
            @Value("${shop.obs.access-log:true}") boolean accessLog,
            @Value("${shop.obs.slow-ms:1000}") long slowMs,
            @Value("${shop.obs.exclude:/actuator/**,/ops/stream}") List<String> exclude) {
        var reg = new FilterRegistrationBean<>(new ApiAccessLogFilter(accessLog, slowMs, exclude));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
