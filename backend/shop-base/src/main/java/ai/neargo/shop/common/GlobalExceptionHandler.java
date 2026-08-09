package ai.neargo.shop.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常 → {@link ApiResult}。
 *
 * <p>HTTP 状态码的取舍：**只有 401 用真状态码**，其余一律 200 + 业务 code。
 * 因为 c-app 的 {@code http-client.ts} 只对 401 做「清 token 跳登录」，
 * 其它情况读的是包体 {@code code} —— 后端再发 4xx/5xx 只会让前端走进 {@code fail} 分支，
 * 拿不到业务错误码，用户看到的是「网络异常」而不是「库存不足」。
 */
@RestControllerAdvice(basePackages = "ai.neargo.shop")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResult<Void> onBiz(BizException e) {
        // 业务异常是预期内的，不打 error 栈，否则告警会被淹没
        log.debug("biz error: {}", e.errorCode());
        return ApiResult.error(e.errorCode().code(), Messages.get(e.errorCode().msgKey(), e.args()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, ConstraintViolationException.class})
    public ApiResult<Void> onInvalid(Exception e) {
        return ApiResult.error(ErrorCode.BAD_REQUEST.code(), firstMessage(e));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ApiResult<Void> onDenied(AccessDeniedException e) {
        log.warn("access denied: {}", e.getMessage());
        return ApiResult.error(ErrorCode.FORBIDDEN.code(), Messages.get(ErrorCode.FORBIDDEN.msgKey()));
    }

    /** 未登录：唯一发真状态码的分支，前端据此清 token（见类注释）。 */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResult<Void> onUnauthorized(UnauthorizedException e) {
        return ApiResult.error(ErrorCode.UNAUTHORIZED.code(), Messages.get(ErrorCode.UNAUTHORIZED.msgKey()));
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Void> onAny(Exception e) {
        log.error("unhandled error", e);
        return ApiResult.error(ErrorCode.INTERNAL_ERROR.code(), Messages.get(ErrorCode.INTERNAL_ERROR.msgKey()));
    }

    private static String firstMessage(Exception e) {
        if (e instanceof MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null) {
            return m.getBindingResult().getFieldError().getDefaultMessage();
        }
        if (e instanceof BindException b && b.getFieldError() != null) {
            return b.getFieldError().getDefaultMessage();
        }
        return Messages.get(ErrorCode.BAD_REQUEST.msgKey());
    }

    /** 未登录/会话失效。单独立一个类型，避免和 Spring Security 的 401 处理搅在一起。 */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException() {
            super("unauthorized");
        }
    }
}
