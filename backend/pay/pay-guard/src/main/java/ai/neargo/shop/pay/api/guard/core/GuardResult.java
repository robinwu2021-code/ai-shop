package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.common.ErrorCode;

/**
 * 一次认人的结果：认出了谁，或者以什么状态回绝、原因是什么。
 *
 * @param principal 认出的调用方；回绝时为空
 * @param status    回绝时的 HTTP 状态
 * @param code      回绝时对外的错误码
 * @param outcome   回绝时请求记录的 {@code outcome}（REJECTED_AUTH / REJECTED_FORBIDDEN / REJECTED_PARAM）
 * @param reason    回绝原因：只进请求记录与日志，<b>不回给调用方</b>
 */
public record GuardResult(PortalPrincipal principal, int status, ErrorCode code, String outcome, String reason) {

    public static GuardResult ok(PortalPrincipal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("principal is required");
        }
        return new GuardResult(principal, 200, null, null, null);
    }

    /** 认不出：对外一律 401，原因只进记录（不当探测器用） */
    public static GuardResult unauthorized(String reason) {
        return new GuardResult(null, 401, ErrorCode.UNAUTHORIZED, "REJECTED_AUTH", reason);
    }

    public boolean ok() {
        return principal != null;
    }
}
