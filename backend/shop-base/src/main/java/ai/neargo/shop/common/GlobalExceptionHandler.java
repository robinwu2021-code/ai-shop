package ai.neargo.shop.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    /**
     * 请求本身不合规：字段校验没过、少了必填参数、body 不是合法 JSON、参数类型不对。
     *
     * <p><b>少一个必填参数原先落到 {@link #onAny} 上，返回「系统开小差了，请稍后再试」</b> ——
     * 那句话是在教人重试，而重试一万次也一样：错的是这次请求，不是服务端。
     * 实测撞上的是核销台的按码搜索（`/biz/pickup/verify/search` 不带 keyword），
     * 返回 500，日志里还挂一条 error 栈，看着像后端崩了。
     *
     * <p>四种异常放在一起，是因为它们对调用方是同一件事：<b>你发的请求有问题</b>。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            ConstraintViolationException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ApiResult<Void> onInvalid(Exception e) {
        // 预期内的调用方错误，不打 error 栈 —— 打了会把真正的告警淹掉
        log.debug("bad request: {}", e.getMessage());
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
        /*
         * **要说清是哪个参数**。只回一句「请求参数有误」，联调时要靠猜；
         * 而这条错误的读者是写调用方的人，不是终端用户 —— 参数名对他有用。
         */
        if (e instanceof MissingServletRequestParameterException m) {
            return Messages.get(ErrorCode.BAD_REQUEST.msgKey()) + "：缺少 " + m.getParameterName();
        }
        if (e instanceof MethodArgumentTypeMismatchException m) {
            return Messages.get(ErrorCode.BAD_REQUEST.msgKey()) + "：" + m.getName() + " 格式不对";
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
