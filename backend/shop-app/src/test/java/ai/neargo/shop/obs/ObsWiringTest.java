package ai.neargo.shop.obs;

import ai.neargo.shop.config.obs.ApiAccessLogFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过滤器真的被装进应用了（TDD-接口耗时与慢日志）。
 *
 * <p>{@link ApiAccessLogFilterTest} 验的是「这段逻辑对不对」，不需要容器。
 * 剩下的那一半 —— <b>它到底有没有被注册</b> —— 只有容器知道，于是有了这个类。
 * 两件事分开，是因为前者占全部判据的九成而容器一秒都不值得为它起。
 *
 * <p><b>刻意不加 {@code @TestPropertySource}、不用 {@code RANDOM_PORT}</b>：
 * 任何一个都会让这个类**自建一个 Spring 上下文**。全量测试里上下文缓存有上限，
 * 多一个就挤掉一个旧的 —— 上一版就是这么把 {@code TopicFlowTest} 搞红的
 * （实测：全量红 2 条，只排除那个类就 2441 跑 0 失败，而它单独跑是绿的）。
 * 保持与其它 {@code @SpringBootTest @ActiveProfiles("test")} 完全相同的配置，
 * 才能复用已经缓存的那个上下文、不新增。
 *
 * <p>所以这个类**不要加任何 Spring 测试注解或属性**。要验新配置，
 * 在 {@link ApiAccessLogFilterTest} 里直接 new 一个过滤器传参，那边不花容器的钱。
 */
@SpringBootTest
@ActiveProfiles("test")
class ObsWiringTest {

    @Autowired
    private FilterRegistrationBean<ApiAccessLogFilter> registration;

    @Test
    @DisplayName("★★★ 过滤器被全局注册、拦所有路径、排在最外层")
    void filterIsRegisteredGlobally() {
        assertThat(registration).as("注册 bean 要在").isNotNull();
        assertThat(registration.getFilter()).isInstanceOf(ApiAccessLogFilter.class);
        // /*：SecurityConfig 有 5 条链，挂进链里要挂五遍、还漏掉不匹配任何链的请求
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        // 最外层：要量的是整个请求，包括认证与数据域解析那几层。
        // 放里面就只量到业务方法，而「登录态解析变慢」恰恰是最难查的一类。
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
