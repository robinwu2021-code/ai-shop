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

    /**
     * 「把人挡在门外」的那几个码。
     *
     * <p><b>为什么只挑这四个升到 WARN</b>：业务异常绝大多数是预期内的
     * （库存不足、券领完了、状态不对），全升会把日志淹掉然后没人再看 —— 那和不打是一回事。
     * 而这四个的共同点是<b>有人被拒了，而他多半不知道为什么</b>，
     * 且它们正是排查「这个账号怎么什么都看不到」时唯一的线索。
     */
    private static final java.util.Set<Integer> REJECTION_CODES = java.util.Set.of(
            ErrorCode.UNAUTHORIZED.code(),        // 10401 没登录
            ErrorCode.TOKEN_EXPIRED.code(),       // 10402 登录过期
            ErrorCode.FORBIDDEN.code(),           // 10403 没权限 / 不在数据域里
            ErrorCode.BIZ_ROLE_FORBIDDEN.code()); // 70006 B 端角色不够

    @ExceptionHandler(BizException.class)
    public ApiResult<Void> onBiz(BizException e) {
        /*
         * 业务异常是预期内的，不打 error 栈，否则告警会被淹没 ——
         * **但「不要 error」不等于「不要可见」**，中间还有 WARN 这一档。
         * 拒绝类走 WARN 并带上方法与路径；其余保持 debug。
         */
        if (REJECTION_CODES.contains(e.errorCode().code())) {
            log.warn("拒绝 {} · {}", e.errorCode(), requestLine());
        } else {
            log.debug("biz error: {}", e.errorCode());
        }
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
    public ApiResult<Void> onInvalid(Exception e, jakarta.servlet.http.HttpServletRequest req) {
        /*
         * **WARN，不是 DEBUG。** 生产跑在 INFO 上，打 debug 等于一行不打。
         *
         * <p>为什么这一行非有不可：入参被拒**在生产上是完全看不见的**。
         * 业务错误一律 200 + 信封里的 code（见类注释），所以 nginx 的 access.log
         * 里它是 200；而 app.log 此前只有这句 debug。2026-08-29 实测：
         * 线上打出好几个 10400，`grep -c 10400 app.log` 是 **0**。
         *
         * <p>这在 15 个端点接上 `@Valid` 那天（945a089e）从「无所谓」变成「要命」——
         * 那笔收紧了入参校验，而线上跑着的是**已经发出去的 App**。如果某个版本真在
         * 传空值，链路会从「走到下游」变成「当场拒」，**而没有任何人会知道**。
         * 「日志里没看到 400」这句话此前什么都不证明。
         *
         * <p>不打 error 栈的判断是对的（预期内的调用方错误，打 error 会把真告警淹掉），
         * 但那是「不要 error」，不是「不要可见」。
         *
         * <p>⚠️ **只记路径与字段名，绝不记 body。** 这些请求体里有手机号、验证码、
         * 收货地址 —— 把它们写进日志，等于把一份 PII 副本堆在磁盘上，
         * 而它比数据库更难管（没有保留期、没有脱敏、谁都能 grep）。
         */
        log.warn("入参被拒 {} {} · {}", req.getMethod(), req.getRequestURI(), rejectedFields(e));
        return ApiResult.error(ErrorCode.BAD_REQUEST.code(), firstMessage(e));
    }

    /**
     * 被拒的字段名（**只有名字，没有值**）。拿不到字段名时退回异常类型简名 ——
     * 那也比一句「参数有误」有用：至少说得出是哪一类。
     */
    private static String rejectedFields(Exception e) {
        java.util.List<org.springframework.validation.FieldError> errors = null;
        if (e instanceof MethodArgumentNotValidException m) {
            errors = m.getBindingResult().getFieldErrors();
        } else if (e instanceof BindException b) {
            errors = b.getFieldErrors();
        }
        if (errors != null && !errors.isEmpty()) {
            /*
             * 带上**约束名**（NotBlank / Size / Pattern…）而不只是字段名：
             * 「phone 没传」与「phone 太长」是两件事，前者多半是端上少填了一个字段，
             * 后者多半是有人在灌垃圾。只看字段名分不出来，而这两种的处置完全不同。
             * getCode() 给的正是注解简名。
             */
            return errors.stream()
                    .map(x -> x.getField() + "(" + x.getCode() + ")")
                    .distinct().limit(10).collect(java.util.stream.Collectors.joining(","));
        }
        if (e instanceof MissingServletRequestParameterException m) {
            return "缺少参数 " + m.getParameterName();
        }
        if (e instanceof MissingRequestHeaderException m) {
            return "缺少请求头 " + m.getHeaderName();
        }
        if (e instanceof MethodArgumentTypeMismatchException m) {
            return "类型不符 " + m.getName();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * {@code @PreAuthorize} 拒绝。
     *
     * <p><b>必须带上路径。</b>此前这里打的是 {@code "access denied: " + e.getMessage()}，
     * 而 Spring 给的 message 通常就是「Access is denied」—— 一行几乎零信息的 WARN，
     * 回答不了唯一要问的那个问题：<b>哪个端点被拒了</b>。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ApiResult<Void> onDenied(AccessDeniedException e) {
        log.warn("判权拒绝 {} · {}", requestLine(), e.getMessage());
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
        /*
         * 只在**带了令牌却被拒**时打 WARN。没带令牌是常态（游客、探测器），
         * 全打会把这行淹掉；而带了令牌被拒才是要查的那件事 ——
         * 过期、被吊销、或者跨端令牌。
         *
         * ⚠️ **只打「带没带」，不打令牌本身**：令牌进了日志就等于会话可被重放，
         * 而日志会被收集转发。同理不打用户号 —— 要按人排查有 LoginAuditor 那条线。
         */
        if (Boolean.TRUE.equals(expired)) {
            log.warn("会话已失效 {} · {}", code, requestLine());
        }
        return ApiResult.error(code.code(), Messages.get(code.msgKey()));
    }

    /**
     * `METHOD /path` —— 拒绝类日志唯一要带的上下文。
     *
     * <p><b>刻意只有这两样</b>：不带 body、不带参数值、不带令牌、不带用户号。
     * 这些请求里有手机号、地址、银行卡号，而日志会被收集转发 ——
     * 一行「修复可观测性」的日志不该自己变成新的泄露口
     * （同 {@code onInvalid} 那一支的取舍，见 InvalidRequestIsVisibleTest）。
     */
    private static String requestLine() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra)) {
            return "(无请求上下文)";
        }
        return sra.getRequest().getMethod() + " " + sra.getRequest().getRequestURI();
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
