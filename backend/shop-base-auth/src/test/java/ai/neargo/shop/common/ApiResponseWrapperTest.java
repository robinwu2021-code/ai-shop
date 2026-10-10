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
    @DisplayName("★★★ 店铺码短链不包 —— 它只发 302，包了会在响应体里留一句没人看的 JSON")
    void shortLinkIsNotWrapped() {
        // 实况：ResponseEntity<Void> 的 body 是 null，仍然走这一层，于是 302 的响应体
        // 变成 {"code":0,"msg":"success","data":null}。浏览器不看 302 的 body，所以看不出来
        assertThat(write("", "/s/SMTBA2", null)).isNull();
        assertThat(write("", "/s/my-store", "x")).isEqualTo("x");
    }

    @Test
    @DisplayName("★★★ 通用短链 /l/ 也不包 —— 命中 302(null body)、未命中失效页(String)，包了后者会渲染成 10500")
    void genericShortLinkIsNotWrapped() {
        assertThat(write("", "/l/ABC1234", null)).isNull();
        assertThat(write("", "/l/ABC1234", "<html>link expired</html>")).isEqualTo("<html>link expired</html>");
        // /l/ 也只认前缀：别的路径照旧包
        assertThat(write("", "/biz/l/ABC", "x")).isInstanceOf(ApiResult.class);
    }

    @Test
    @DisplayName("★★ /s/ 也只认前缀：/search 与 /biz/s/... 照旧要包")
    void shortLinkPrefixMustBeExact() {
        assertThat(write("", "/search?kw=x", "x")).isInstanceOf(ApiResult.class);
        assertThat(write("", "/biz/s/SMTBA2", "x")).isInstanceOf(ApiResult.class);
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
