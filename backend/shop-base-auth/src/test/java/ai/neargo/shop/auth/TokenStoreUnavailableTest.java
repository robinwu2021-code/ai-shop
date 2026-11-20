package ai.neargo.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 会话存储连不上时，认证过滤器回 503（TDD-网关层与统一权限 AC26 后半；开发计划 §2.6b 第 13 条）。
 *
 * <p>不能回 401：端上对 401 的动作是清令牌、跳登录页，存储抖一下就会把在线的人全踢下线。
 */
@DisplayName("认证 · 会话存储不可用")
class TokenStoreUnavailableTest {

    private final AtomicInteger lookups = new AtomicInteger();

    /** 任何调用都抛连接失败的会话存储。 */
    private final TokenStore broken = (TokenStore) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {TokenStore.class}, (proxy, method, args) -> {
                lookups.incrementAndGet();
                throw new IllegalStateException("Unable to connect to Redis");
            });

    private final AbstractTokenAuthFilter filter = new AbstractTokenAuthFilter(broken, Realm.OPERATOR) {
        @Override
        protected String clientCode(HttpServletRequest req) {
            return "test";
        }

        @Override
        protected Authenticated authenticate(LoginUser sessionUser) {
            return new Authenticated(sessionUser, List.of());
        }
    };

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("★★★ 带令牌而存储连不上：503 + 统一包体 + Retry-After；不进后面的链，不当成没登录")
    void storeFailureAnswers503() throws Exception {
        var request = new MockHttpServletRequest("GET", "/ops/auth/me");
        request.addHeader("Authorization", "Bearer otk_whatever");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentAsString()).startsWith("{\"code\":10500,").endsWith(",\"data\":null}");
        assertThat(chain.getRequest()).as("请求不应继续往下走").isNull();
        assertThat(request.getAttribute(AbstractTokenAuthFilter.TOKEN_EXPIRED_ATTR))
                .as("不能打上「令牌过期」：那会让端上清掉令牌").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("★★ 不带令牌的请求不碰存储：存储坏着，游客照常往下走")
    void anonymousRequestNeverTouchesTheStore() throws Exception {
        var request = new MockHttpServletRequest("GET", "/mp/goods");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(lookups).hasValue(0);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
