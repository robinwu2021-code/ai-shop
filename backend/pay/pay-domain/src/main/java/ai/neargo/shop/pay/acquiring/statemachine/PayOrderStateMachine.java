package ai.neargo.shop.pay.acquiring.statemachine;

import static ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus.AWAITING_PAYMENT;
import static ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus.CLOSED;
import static ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus.CREATED;
import static ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus.FAILED;
import static ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus.PARTIALLY_REFUNDED;
import static ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus.REFUNDED;
import static ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus.SUCCEEDED;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 支付单迁移表（代码清单 §2.2）。不在表里的迁移一律 {@code PAY_ORDER_STATE}：
 * 尤其 <b>CLOSED → SUCCEEDED 不存在</b> —— 关单后到账是一笔要处置的异常（{@code LATE_PAYMENT}），不是一次静默成功（R18）。
 */
public final class PayOrderStateMachine {

    private static final Map<PayOrderEvent, Map<PayOrderStatus, PayOrderStatus>> TABLE = new EnumMap<>(PayOrderEvent.class);

    static {
        TABLE.put(PayOrderEvent.ATTEMPT_OPENED, Map.of(CREATED, AWAITING_PAYMENT, AWAITING_PAYMENT, AWAITING_PAYMENT,
                FAILED, AWAITING_PAYMENT));
        TABLE.put(PayOrderEvent.SUCCEEDED, Map.of(CREATED, SUCCEEDED, AWAITING_PAYMENT, SUCCEEDED, FAILED, SUCCEEDED));
        TABLE.put(PayOrderEvent.FAILED, Map.of(CREATED, FAILED, AWAITING_PAYMENT, FAILED));
        TABLE.put(PayOrderEvent.CLOSED, Map.of(CREATED, CLOSED, AWAITING_PAYMENT, CLOSED, FAILED, CLOSED));
        TABLE.put(PayOrderEvent.PARTIALLY_REFUNDED, Map.of(SUCCEEDED, PARTIALLY_REFUNDED,
                PARTIALLY_REFUNDED, PARTIALLY_REFUNDED));
        TABLE.put(PayOrderEvent.REFUNDED, Map.of(SUCCEEDED, REFUNDED, PARTIALLY_REFUNDED, REFUNDED));
    }

    private PayOrderStateMachine() {
    }

    public static PayOrderStatus next(PayOrderStatus from, PayOrderEvent event) {
        PayOrderStatus to = TABLE.get(event).get(from);
        if (to == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_STATE, from.name());
        }
        return to;
    }

    public static boolean allows(PayOrderStatus from, PayOrderEvent event) {
        return TABLE.get(event).containsKey(from);
    }

    /** 可以再开通道尝试的状态 */
    public static Set<PayOrderStatus> payable() {
        return Set.of(CREATED, AWAITING_PAYMENT, FAILED);
    }
}
