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
 * <p>两个例外原样透传：已经是 {@link ApiResult}（自定义 code 的场景）、以及 {@code String}
 * —— {@code String} 走的是 {@code StringHttpMessageConverter}，包成对象再交给它会抛
 * {@code ClassCastException}，这是 ResponseBodyAdvice 的经典坑，故在 {@link #supports} 就排除掉。
 */
@RestControllerAdvice(basePackages = "ai.neargo.shop")
public class ApiResponseWrapper implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !String.class.equals(returnType.getParameterType());
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
