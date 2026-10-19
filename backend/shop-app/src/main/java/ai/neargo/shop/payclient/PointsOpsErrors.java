package ai.neargo.shop.payclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.function.Supplier;

/**
 * Only declared domain rejections become business errors; invariants still reach error monitoring.
 */
public final class PointsOpsErrors {
    private PointsOpsErrors() {}

    public static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (IllegalArgumentException rejected) {
            String code = rejected.getMessage();
            if (code == null || !code.matches("POINTS_[A-Z0-9_]{1,100}")) throw rejected;
            if (code.equals("POINTS_SELF_APPROVE"))
                throw BizException.of(ErrorCode.POINTS_SELF_REVIEW);
            if (code.contains("REVISION") || code.contains("CONFLICT"))
                throw BizException.of(ErrorCode.POINTS_REVISION_CHANGED);
            if (code.contains("UNSUPPORTED") || code.contains("INSUFFICIENT"))
                throw BizException.of(ErrorCode.POINTS_FUNDS_UNAVAILABLE);
            if (code.contains("EVIDENCE") || code.contains("RECEIPT"))
                throw BizException.of(ErrorCode.POINTS_EVIDENCE_REQUIRED);
            throw BizException.of(ErrorCode.POINTS_OPERATION_REJECTED, code);
        } catch (IllegalStateException unavailable) {
            if ("POINTS_FUNDS_GATEWAY_REQUIRED".equals(unavailable.getMessage()))
                throw BizException.of(ErrorCode.POINTS_FUNDS_UNAVAILABLE);
            throw unavailable;
        }
    }
}
