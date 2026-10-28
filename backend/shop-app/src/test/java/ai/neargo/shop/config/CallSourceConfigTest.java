package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * 来源拦截器不碰内部调用（开发计划 M2.6）：{@code /internal/**} 的来源由 {@code InternalCallFilter} 从上游接力，
 * 拦截器要是也绑一次，就会用本次请求自己的信息（端码 UNKNOWN、路径 /internal/…）把接力来的那份顶掉。
 */
class CallSourceConfigTest {

    /** 拿出注册结果（{@code getInterceptors} 是 protected） */
    private static final class Registry extends InterceptorRegistry {
        List<Object> all() {
            return getInterceptors();
        }
    }

    @Test
    @DisplayName("★★★ 外部请求绑来源，/internal/** 不绑")
    void internalPathsAreExcluded() {
        Registry registry = new Registry();
        new CallSourceConfig("shop-app").addInterceptors(registry);

        MappedInterceptor mapped = (MappedInterceptor) registry.all().getFirst();
        assertThat(mapped.matches(parsed("/mp/orders/SO1/pay"))).isTrue();
        assertThat(mapped.matches(parsed("/internal/job/x/run"))).isFalse();
    }

    /** 拦截器匹配要的是 DispatcherServlet 预先解析好的路径 */
    private static MockHttpServletRequest parsed(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        ServletRequestPathUtils.parseAndCache(r);
        return r;
    }
}
