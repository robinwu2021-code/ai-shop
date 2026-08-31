package ai.neargo.shop.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把 Controller 的返回值统一包成 {@link ApiResult}。Controller 因此只写业务对象，不写 {@code ApiResult.ok(...)}。
 *
 * <p><b>{@code /internal/**} 整个不包</b>：那些口的调用方是独立调度器，不是浏览器。
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

    /** 内部端点所在的包。这里面的返回值原样出去，不套 {@link ApiResult} 信封。 */
    private static final String INTERNAL_PACKAGE = "ai.neargo.shop.portal.internal.";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (String.class.equals(returnType.getParameterType())) {
            return false;
        }
        // 按包排除，不按路径 —— supports() 这里拿不到请求，只拿得到方法。
        // 内部端点全部落在这一个包里，进出都要经过它
        return !returnType.getContainingClass().getName().startsWith(INTERNAL_PACKAGE);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResult<?>) {
            return body;
        }
        return ApiResult.ok(body);
    }
}
