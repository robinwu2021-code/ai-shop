package ai.neargo.shop.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 全局信封的放行规则：{@code /internal/**} 原样出去，其余包成 {@link ApiResult}。
 *
 * <p>规则此前按包名判（只放 {@code …portal.internal.}），支付域独立进程的内部端点不在那个包里 ——
 * 它没被包住只是因为那个进程没扫描到信封类。这一组直接钉住「按路径」。
 */
@DisplayName("全局响应信封的放行规则")
class ApiResponseWrapperTest {

    private final ApiResponseWrapper wrapper = new ApiResponseWrapper();

    private Object write(String contextPath, String uri, Object body) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setContextPath(contextPath);
        return wrapper.beforeBodyWrite(body, null, null, null, new ServletServerHttpRequest(req), null);
    }

    @Test
    @DisplayName("★★★ 支付域独立进程的内部口不包 —— 包了的话对方解出来是字段全 null 的对象，而且不报错")
    void payInternalIsNotWrapped() {
        List<String> body = List.of("FR1");
        assertThat(write("", "/internal/pay/fee-rules", body)).isSameAs(body);
    }

    @Test
    @DisplayName("★★ 调度器与 AI 数据口同样不包（与改之前按包放行的结果一致）")
    void otherInternalIsNotWrapped() {
        assertThat(write("", "/internal/job/declarations", "x-list")).isEqualTo("x-list");
        assertThat(write("", "/internal/ai/v1/goods/list", "x")).isEqualTo("x");
    }

    @Test
    @DisplayName("★★★ 三端接口照旧包成 ApiResult")
    void publicApiIsWrapped() {
        assertThat(write("", "/biz/goods/list", "x")).isInstanceOf(ApiResult.class);
        assertThat(write("", "/mp/goods", "x")).isInstanceOf(ApiResult.class);
    }

    @Test
    @DisplayName("★★ 只认路径前缀：/internalx 与 /biz/internal/... 不算内部口")
    void prefixMustBeExact() {
        assertThat(write("", "/internalx/pay", "x")).isInstanceOf(ApiResult.class);
        assertThat(write("", "/biz/internal/pay", "x")).isInstanceOf(ApiResult.class);
    }

    @Test
    @DisplayName("★ 有 context path 时先去掉再判")
    void contextPathIsStripped() {
        assertThat(write("/api", "/api/internal/pay/fee-rules", "x")).isEqualTo("x");
        assertThat(write("/api", "/api/biz/goods", "x")).isInstanceOf(ApiResult.class);
    }
}
