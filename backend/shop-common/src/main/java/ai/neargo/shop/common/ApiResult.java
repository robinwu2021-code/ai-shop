package ai.neargo.shop.common;

/**
 * 统一响应包 {@code {code, msg, data}}。
 *
 * <p><b>为什么不直接用 neargo 的 {@code Result}</b>（powerbank 用的是那个）：
 * neargo 契约是 {@code {code, message, data}} + 分页 {@code {list, total}}，
 * 而 ai-shop 的 C 端 {@code c-app/src/api/http-client.ts} 与全部 mock 已按
 * {@code {code, msg, data}} + {@code {records, total, page, size}} 写死并落地了 45 个端点。
 * 改前端的成本远高于在后端保留一个 20 行的包装类，故 ai-shop 自持契约（TDD-backend §12 S4 已按此拍板）。
 *
 * <p>Controller 一律直接返回业务对象，由 {@link ApiResponseWrapper} 自动包裹；
 * 只有需要自定义 code 时才手写 {@code ApiResult.error(...)}。
 *
 * @param code 0=成功，非 0 见 {@link ErrorCode} 分段
 * @param msg  给人看的提示（已按 Accept-Language 本地化）
 * @param data 业务载荷
 */
public record ApiResult<T>(int code, String msg, T data) {

    public static final int OK = 0;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(OK, "success", data);
    }

    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> error(int code, String msg) {
        return new ApiResult<>(code, msg, null);
    }
}
