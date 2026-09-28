package ai.neargo.shop.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把 Controller 的返回值统一包成 {@link ApiResult}。Controller 因此只写业务对象，不写 {@code ApiResult.ok(...)}。
 *
 * <p><b>{@code /internal/**} 整个不包</b>（按请求路径判，见 {@link #INTERNAL_PATH_PREFIX}）：
 * 那些口的调用方是独立调度器与其它进程，不是浏览器。
 * 它按自己的契约读 {@code {status, detail, error}}，套上信封之后读到的是
 * {@code {code, msg, data}} —— <b>解析不报错，只是每个字段都是 null</b>，
 * 于是「任务声明」变成一条条空记录。2026-08-27 部署时撞到：调度器起来了、
 * 端点 200、日志只有一行 IllegalArgumentException，中间隔着这层看不见的包装。
 *
 * <p>两个例外原样透传：已经是 {@link ApiResult}（自定义 code 的场景）、以及 {@code String}
 * —— {@code String} 走的是 {@code StringHttpMessageConverter}，包成对象再交给它会抛
 * {@code ClassCastException}，这是 ResponseBodyAdvice 的经典坑，故在 {@link #supports} 就排除掉。
 */
@RestControllerAdvice(basePackages = "ai.neargo.shop")
public class ApiResponseWrapper implements ResponseBodyAdvice<Object> {

    /**
     * 内部端点的路径前缀。这下面的返回值原样出去，不套 {@link ApiResult} 信封。
     *
     * <p><b>按路径判，不按包判</b>（2026-09-24 改）。此前按包名放行（只放 {@code …portal.internal.}），
     * 而支付域独立进程的 {@code InternalPayEndpoint} 在 {@code …pay.svc} 包里 ——
     * 它没被包住，只是因为那个进程恰好没扫描到本类（本类就在它的 classpath 上）。
     * 谁哪天改了扫描范围，pay 的内部口就会静默变成「200、字段全 null」。
     * 契约本来就是按路径定的（{@code /internal/**} 用共享密钥、不走用户鉴权），判据也就按路径。
     */
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    /**
     * 店铺码短链 {@code /s/<码>}（TDD-店铺码与分享 §3.5）。这下面只有 302，没有业务响应。
     *
     * <p><b>为什么非得在这儿排除一次。</b>那个端点返回的是 {@code ResponseEntity<Void>} ——
     * 看起来「没有响应体，{@code ResponseBodyAdvice} 不会触发」。实测不是：
     * body 为 null 仍然走这一层，于是 302 的响应体里出现了
     * {@code {"code":0,"msg":"success","data":null}}。浏览器不看 302 的 body，
     * 所以这个错误在浏览器里完全看不出来，只有断言响应体为空才抓得到
     * （同一个坑第二次：上次是内部口变成「200 + 字段全 null」）。
     *
     * <p>不用 {@code sendRedirect} 绕过去，是因为绕过去只解决这一个方法：
     * 下一个在 {@code /s/} 下加端点的人会再踩一遍。按路径排除才是把边界写成一条断言。
     */
    private static final String SHORT_LINK_PATH_PREFIX = "/s/";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // String 走 StringHttpMessageConverter，包成对象再交给它会 ClassCastException —— 这里就排除
        return !String.class.equals(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // supports() 拿不到请求，所以路径在这里判
        if (body instanceof ApiResult<?> || isInternal(request) || isShortLink(request)) {
            return body;
        }
        return ApiResult.ok(body);
    }

    /** 请求路径（去掉 context path 之后）是否在 {@code /internal/} 之下 */
    static boolean isInternal(ServerHttpRequest request) {
        return pathOf(request).startsWith(INTERNAL_PATH_PREFIX);
    }

    /** 请求路径（去掉 context path 之后）是否在 {@code /s/} 之下 */
    static boolean isShortLink(ServerHttpRequest request) {
        return pathOf(request).startsWith(SHORT_LINK_PATH_PREFIX);
    }

    /** 去掉 context path 的请求路径。两处判前缀共用一份 —— 分开写迟早只改一处 */
    private static String pathOf(ServerHttpRequest request) {
        String path = request.getURI().getRawPath();
        if (request instanceof ServletServerHttpRequest servlet) {
            String contextPath = servlet.getServletRequest().getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && path != null && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
        }
        return path == null ? "" : path;
    }
}
