package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.trade.entity.OrdStatusLog;

/**
 * 状态变更的操作者（谁推进的）。写进 {@code ord_status_log} 的 operatorType/operatorNo，
 * 纠纷时要能分清是买家、商家、平台还是系统点的。
 *
 * <p>{@code type} 复用 {@link OrdStatusLog} 的 {@code BY_*} 常量，保持与现状日志口径一致。
 */
public record Operator(String type, String no) {

    public static Operator user(String no) {
        return new Operator(OrdStatusLog.BY_USER, no);
    }

    public static Operator merchant(String no) {
        return new Operator(OrdStatusLog.BY_MERCHANT, no);
    }

    public static Operator platform(String no) {
        return new Operator(OrdStatusLog.BY_PLATFORM, no);
    }

    /** 系统（定时任务/回调）发起，无操作人号。 */
    public static Operator system() {
        return new Operator(OrdStatusLog.BY_SYSTEM, null);
    }
}
