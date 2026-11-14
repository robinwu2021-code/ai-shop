package ai.neargo.shop.pay.api.common;

/**
 * 开放 API 与收银台的统一响应（接口清单 §0）：{@code {code, message, data, requestId}}。
 * 不走平台的 {@code ApiResponseWrapper}（那里按路径跳过这两个面，避免双层信封）。
 */
public record PortalResponse<T>(int code, String message, T data, String requestId) {

    public static final int OK = 0;

    public static <T> PortalResponse<T> ok(T data, String requestId) {
        return new PortalResponse<>(OK, "OK", data, requestId);
    }
}
