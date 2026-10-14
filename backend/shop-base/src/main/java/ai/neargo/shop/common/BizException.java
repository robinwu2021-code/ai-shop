package ai.neargo.shop.common;

/**
 * 业务异常：Service 层唯一的「预期内失败」出口。
 *
 * <p>约定：**不允许 Service 直接返回 {@code null} 或塞错误码进正常返回值** ——
 * 那样调用方要么忘判、要么每层都判。抛这个，由 {@link GlobalExceptionHandler} 统一翻成 {@link ApiResult}。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object[] args;

    public BizException(ErrorCode errorCode, Object... args) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.args = args;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object[] args() {
        return args;
    }

    public static BizException of(ErrorCode code, Object... args) {
        return new BizException(code, args);
    }
}
