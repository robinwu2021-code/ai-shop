package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.Messages;
import ai.neargo.shop.common.RequestLogContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * 过滤器里输出「没有操作权限」。包体与 {@code GlobalExceptionHandler#onDenied} 相同：
 * 同一个错误码、同一句文案、同样不改 HTTP 状态码 —— 端上分不出是提前判权拒的还是方法注解拒的。
 *
 * <p>不经 Spring MVC 的异常解析器：过滤器在处理方法匹配之前，那时没有处理方法可供它定位异常处理。
 */
public class GatewayErrorWriter {

    private final ObjectMapper json;

    public GatewayErrorWriter(ObjectMapper json) {
        this.json = json;
    }

    /** 与 {@code GlobalExceptionHandler#onBiz} 同样的包体：错误码 + 带参数的文案，不改 HTTP 状态码。 */
    public void write(HttpServletRequest request, HttpServletResponse response, BizException error) throws IOException {
        ApiResult<Void> body = ApiResult.error(error.errorCode().code(),
                Messages.get(error.errorCode().msgKey(), error.args()));
        request.setAttribute(RequestLogContext.BUSINESS_CODE, body.code());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(body));
    }
}
