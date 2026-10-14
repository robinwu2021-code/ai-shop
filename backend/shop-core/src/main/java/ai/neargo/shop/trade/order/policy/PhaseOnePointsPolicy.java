package ai.neargo.shop.trade.order.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;

/** Phase one cash orders must not depend on the unfinished points system or erase historical obligations. */
public final class PhaseOnePointsPolicy {
    private PhaseOnePointsPolicy() {
    }

    /** Kept server-side so an older client cannot enable points by sending its own feature flag. */
    public static boolean enabled() {
        return false;
    }

    /** Reject unsupported deductions before stock, coupons or appointments are reserved. */
    public static void validate(Long usePoints) {
        if (usePoints != null && usePoints != 0L) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** A historical liability requires manual resolution while automatic points reversal is unavailable. */
    public static void requireCashHistory(OrdSubOrder order) {
        if (hasHistoricalPoints(order)) {
            throw BizException.of(ErrorCode.ORDER_POINTS_MANUAL_REQUIRED);
        }
    }

    /** Immutable snapshots select the historical read path without querying points for new cash orders. */
    public static boolean hasHistoricalPoints(OrdSubOrder order) {
        return order != null && ((order.getPointsDeduct() != null && order.getPointsDeduct() > 0)
                || (order.getPointsDeductMinor() != null && order.getPointsDeductMinor() > 0)
                || (order.getPointsFeeMinor() != null && order.getPointsFeeMinor() > 0)
                || Boolean.TRUE.equals(order.getPointsGranted()));
    }
}
