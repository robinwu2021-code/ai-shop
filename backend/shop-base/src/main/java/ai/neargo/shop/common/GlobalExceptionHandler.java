package ai.neargo.shop.common;

import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
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
     * <p>这些异常放在一起，是因为它们对调用方是同一件事：<b>你发的请求有问题</b>。
     *
     * <p><b>缺请求头是 2026-08-28 补进来的</b>：缺参数早就在列，缺请求头却不在，
     * 于是 {@code @RequestHeader("Authorization")} 少一个头会掉进兜底那条
     * 变成 10500「服务器内部错误」—— C 端的 {@code /mp/user/logout} 与
     * {@code /mp/user/token/refresh} 实测就是这样。<b>客户端的错在监控里长成服务端故障</b>，
     * 而真正的服务端故障就淹在里面。
     *
     * <p>只补 {@link MissingRequestHeaderException}，<b>不补它的父类
     * {@code ServletRequestBindingException}</b>：那个父类还包含
     * {@code MissingPathVariableException}，而路径变量缺失是**映射写错了**，
     * 是货真价实的服务端 bug，500 才是对的。一把抓会把它一起洗白。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            ConstraintViolationException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
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
    /**
     * 401。**分「没登录」与「登录过期」两种** —— 端上要做的事不同：
     * 前者引导去登录页（游客逛店是常态），后者要说「登录已过期」<b>并清掉本地那份 token</b>。
     *
     * <p>为什么这里也要判一次：{@code ApiAuthEntryPoint} 只管得到需要认证的链（`/biz`、`/ops`）。
     * <b>`/mp/**` 是 permitAll</b>，请求会一路走到控制器、由 `currentUser()` 抛这个异常 ——
     * 于是同一个「会话没了」在 C 端说成 10401、在 B 端说成 10402。
     * 当初那条修复写的是「两条链一起改」，而 C 端这一半漏在了 permitAll 后面。
     */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResult<Void> onUnauthorized(UnauthorizedException e) {
        Boolean expired = currentRequestAttr(ConsumerTokenAuthFilter.TOKEN_EXPIRED_ATTR);
        ErrorCode code = Boolean.TRUE.equals(expired) ? ErrorCode.TOKEN_EXPIRED : ErrorCode.UNAUTHORIZED;
        return ApiResult.error(code.code(), Messages.get(code.msgKey()));
    }

    /** 取当前请求上的属性。拿不到请求（异步线程、单测直调）时返回 null，不抛 */
    @SuppressWarnings("unchecked")
    private static <T> T currentRequestAttr(String name) {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra)) {
            return null;
        }
        return (T) sra.getRequest().getAttribute(name);
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
