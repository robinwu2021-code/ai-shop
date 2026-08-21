package ai.neargo.shop.auth;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证失败的统一出口。<b>401 带包体，且区分「没登录」与「登录过期」</b>。
 *
 * <p>此前用的是 Spring 自带的 {@code HttpStatusEntryPoint}：只发状态码、<b>响应体是空的</b>。
 * 于是这两件事在端上长得一模一样：
 * <ul>
 *   <li><b>没带令牌</b>——游客在逛，引导他去登录页就行，这是常态</li>
 *   <li><b>令牌过期/被吊销</b>——他本来是登录着的，要说「登录已过期」<b>并清掉本地那份 token</b></li>
 * </ul>
 *
 * <p>分不出来的代价是具体的：B 端真链路联调抓到的一条缺陷，就是把过期说成了
 * 「没权限」——<b>一个让人重新登录，一个让人去找老板要权限，下一步动作完全相反</b>。
 *
 * <p><b>状态码仍然是 401</b>：端上的 http-client 只对 401 做「清 token 跳登录」，
 * 改成 200 + 业务码会让那条既有逻辑整个失效（见 {@code GlobalExceptionHandler} 的类注释）。
 * 这里加的是包体，不是换状态码。
 */
public class ApiAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse resp, AuthenticationException e)
            throws IOException {
        boolean expired = Boolean.TRUE.equals(req.getAttribute(ConsumerTokenAuthFilter.TOKEN_EXPIRED_ATTR));
        ErrorCode code = expired ? ErrorCode.TOKEN_EXPIRED : ErrorCode.UNAUTHORIZED;

        resp.setStatus(HttpStatus.UNAUTHORIZED.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        /*
         * 手写 JSON 而不是注入 ObjectMapper：这一层在 Spring MVC 之外，
         * 拿不到消息转换器；而这个结构只有三个字段，且必须与 ApiResult 逐字节一致 ——
         * 端上是同一段代码在解析它。
         */
        resp.getWriter().write(
                "{\"code\":" + code.code()
                        + ",\"msg\":\"" + escape(Messages.get(code.msgKey()))
                        + "\",\"data\":null}");
    }

    /** 文案里出现引号或反斜杠会把这段 JSON 撕坏 —— 三语文案是可改的，不能假设它们干净 */
    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
