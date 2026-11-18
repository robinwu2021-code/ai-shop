package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
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

    public void forbidden(HttpServletResponse response) throws IOException {
        ApiResult<Void> body = ApiResult.error(ErrorCode.FORBIDDEN.code(), Messages.get(ErrorCode.FORBIDDEN.msgKey()));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(body));
    }
}
